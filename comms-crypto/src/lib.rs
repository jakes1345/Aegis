//! End-to-end encryption for Aegis comms.
//!
//! Two layers, both standard constructions:
//!
//! 1. **Olm double ratchet** (vodozemac, the audited Rust implementation used by
//!    Matrix), in its version-2 session format, which authenticates every
//!    message with a full-length HMAC rather than libolm's truncated one. Both
//!    ends are Aegis, so libolm interoperability is not needed. A long-term identity (Ed25519 signing key + Curve25519 identity
//!    key), signed one-time and fallback keys, and per-peer sessions that ratchet
//!    forward with every message. This gives forward secrecy and post-compromise
//!    security for message content.
//!
//! 2. **Sealed envelopes**: every Olm message is wrapped for the relay in an
//!    anonymous box to the recipient's sealing key (ephemeral X25519 → HKDF-SHA256
//!    → ChaCha20-Poly1305, the `crypto_box_seal` construction). The relay stores
//!    and forwards envelopes it cannot open, and never learns who sent one.
//!
//! Everything a device owns (account, sealing secret, sessions) is kept in one
//! [`Identity`], serialised by [`Identity::pickle`] under a 32-byte key the app
//! keeps in the Android Keystore. Nothing here touches the network or disk.

use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;

use base64::engine::general_purpose::{STANDARD as B64, URL_SAFE_NO_PAD as B64URL};
use base64::Engine;
use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use hkdf::Hkdf;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256, Sha512};
use vodozemac::olm::{Account, AccountPickle, DecryptionError, OlmMessage, Session, SessionConfig, SessionPickle};
use vodozemac::{Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature};
use x25519_dalek::{PublicKey as XPublicKey, StaticSecret};
use zeroize::Zeroizing;

uniffi::setup_scaffolding!();

// ── Errors ─────────────────────────────────────────────────────────────────

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CryptoError {
    #[error("bad key: {reason}")]
    BadKey { reason: String },
    #[error("bad signature")]
    BadSignature,
    #[error("no session with this peer")]
    NoSession,
    /// A normal (post-pre-key) message arrived from `sender_curve25519` but no
    /// session here can read it: the app should ask that peer to start a new one.
    #[error("no session can read a message from {sender_curve25519}")]
    NoSessionFor { sender_curve25519: String },
    /// A message from `sender_curve25519` opened, but no session could decrypt it
    /// and it could not start a new one (for example, it was built on a one-time
    /// key this device no longer holds). The app should ask that peer for a fresh
    /// session rather than keep failing on every message after it.
    #[error("could not decrypt a message from {sender_curve25519}: {reason}")]
    DecryptFrom { sender_curve25519: String, reason: String },
    /// A message this device has already decrypted (its key is spent): a
    /// redelivery or a replay. Nothing is wrong with the session; drop it.
    #[error("message already received")]
    Duplicate,
    #[error("could not decrypt: {reason}")]
    Decrypt { reason: String },
    #[error("could not encrypt: {reason}")]
    Encrypt { reason: String },
    #[error("bad pickle: {reason}")]
    BadPickle { reason: String },
    #[error("malformed envelope: {reason}")]
    Envelope { reason: String },
}

type Result<T> = std::result::Result<T, CryptoError>;

// ── Public records ─────────────────────────────────────────────────────────

/// One published one-time (or fallback) key: the Curve25519 key and the owner's
/// Ed25519 signature over it, so a relay cannot substitute its own.
#[derive(Clone, Debug, Serialize, Deserialize, uniffi::Record)]
pub struct SignedKey {
    pub id: String,
    pub key: String,
    pub signature: String,
}

/// Everything another device needs to start a session with this one.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PublicBundle {
    /// Long-term signing key, base64. This is the identity people verify.
    pub ed25519: String,
    /// Long-term Curve25519 identity key, base64 (Olm).
    pub curve25519: String,
    /// X25519 sealing key, base64: envelopes to this device are boxed to it.
    pub sealing: String,
    /// Signature over `curve25519 || sealing` by `ed25519`, base64.
    pub signature: String,
    /// Fallback key, used when the relay has run out of one-time keys.
    pub fallback: Option<SignedKey>,
    /// One-time keys generated and not yet marked published.
    pub one_time_keys: Vec<SignedKey>,
}

/// The public keys of a peer, as fetched from the relay or read from a QR code.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PeerKeys {
    pub ed25519: String,
    pub curve25519: String,
    pub sealing: String,
    pub signature: String,
}

/// The result of opening an envelope.
#[derive(Clone, Debug, uniffi::Record)]
pub struct Decrypted {
    /// The sender's Curve25519 identity key, base64. The app maps it to a contact.
    pub sender_curve25519: String,
    pub plaintext: Vec<u8>,
    /// True when this message created a new Olm session (a pre-key message).
    pub new_session: bool,
}

// ── Sealed envelope ────────────────────────────────────────────────────────

const ENVELOPE_VERSION: u8 = 1;
const SEAL_INFO: &[u8] = b"aegis-comms-seal-v1";

/// Serialised inside the sealed box; only the recipient reads it.
#[derive(Serialize, Deserialize)]
struct Inner {
    /// Sender's Curve25519 identity key, base64.
    s: String,
    /// Olm message type: 0 pre-key, 1 normal.
    t: u8,
    /// Olm ciphertext, base64.
    c: String,
}

/// `crypto_box_seal`-style anonymous box: ephemeral X25519 with the recipient's
/// static key, HKDF over the shared secret bound to both public keys, then
/// ChaCha20-Poly1305 with a zero nonce (the key is single-use).
fn seal(recipient: &XPublicKey, plaintext: &[u8]) -> Vec<u8> {
    let eph = StaticSecret::random_from_rng(&mut rand::thread_rng());
    let eph_pub = XPublicKey::from(&eph);
    let shared = eph.diffie_hellman(recipient);
    let key = seal_key(shared.as_bytes(), eph_pub.as_bytes(), recipient.as_bytes());
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
    let ct = cipher
        .encrypt(Nonce::from_slice(&[0u8; 12]), plaintext)
        .expect("ChaCha20-Poly1305 encryption cannot fail on in-memory data");
    let mut out = Vec::with_capacity(1 + 32 + ct.len());
    out.push(ENVELOPE_VERSION);
    out.extend_from_slice(eph_pub.as_bytes());
    out.extend_from_slice(&ct);
    out
}

fn unseal(secret: &StaticSecret, envelope: &[u8]) -> Result<Vec<u8>> {
    if envelope.len() < 1 + 32 + 16 {
        return Err(CryptoError::Envelope { reason: "too short".into() });
    }
    if envelope[0] != ENVELOPE_VERSION {
        return Err(CryptoError::Envelope { reason: format!("unknown version {}", envelope[0]) });
    }
    let mut eph_bytes = [0u8; 32];
    eph_bytes.copy_from_slice(&envelope[1..33]);
    let eph_pub = XPublicKey::from(eph_bytes);
    let me = XPublicKey::from(secret);
    let shared = secret.diffie_hellman(&eph_pub);
    let key = seal_key(shared.as_bytes(), eph_pub.as_bytes(), me.as_bytes());
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
    cipher
        .decrypt(Nonce::from_slice(&[0u8; 12]), &envelope[33..])
        .map_err(|_| CryptoError::Envelope { reason: "not addressed to this device, or tampered".into() })
}

fn seal_key(shared: &[u8], eph_pub: &[u8], recipient_pub: &[u8]) -> Zeroizing<[u8; 32]> {
    let mut salt = Vec::with_capacity(64);
    salt.extend_from_slice(eph_pub);
    salt.extend_from_slice(recipient_pub);
    let hk = Hkdf::<Sha256>::new(Some(&salt), shared);
    let mut key = Zeroizing::new([0u8; 32]);
    hk.expand(SEAL_INFO, key.as_mut()).expect("32 bytes is a valid HKDF output length");
    key
}

// ── Identity ───────────────────────────────────────────────────────────────

/// Serialised form of everything a device owns.
#[derive(Serialize, Deserialize)]
struct State {
    v: u8,
    account: AccountPickle,
    sealing_secret: [u8; 32],
    /// Peer Curve25519 key (base64) → sessions, newest first.
    sessions: HashMap<String, Vec<SessionPickle>>,
    /// Session ids of pre-key messages already accepted (absent in older pickles).
    #[serde(default)]
    used_prekeys: VecDeque<String>,
}

struct Inner2 {
    account: Account,
    sealing_secret: StaticSecret,
    sessions: HashMap<String, Vec<Session>>,
    used_prekeys: VecDeque<String>,
}

/// How many accepted pre-key session ids are remembered to refuse replays.
const MAX_USED_PREKEYS: usize = 2000;

/// A device's cryptographic identity: its Olm account, its sealing key pair and
/// its sessions with every peer. All methods are safe to call from any thread.
#[derive(uniffi::Object)]
pub struct Identity {
    inner: Mutex<Inner2>,
}

const MAX_SESSIONS_PER_PEER: usize = 5;

#[uniffi::export]
impl Identity {
    /// A brand-new identity with fresh random keys.
    #[uniffi::constructor]
    pub fn create() -> std::sync::Arc<Self> {
        let mut account = Account::new();
        account.generate_fallback_key();
        std::sync::Arc::new(Self {
            inner: Mutex::new(Inner2 {
                account,
                sealing_secret: StaticSecret::random_from_rng(&mut rand::thread_rng()),
                sessions: HashMap::new(),
                used_prekeys: VecDeque::new(),
            }),
        })
    }

    /// Restores an identity from [`Identity::pickle`] output under the same key.
    #[uniffi::constructor]
    pub fn restore(pickle: String, key: Vec<u8>) -> Result<std::sync::Arc<Self>> {
        let key = key_from(&key)?;
        let blob = B64
            .decode(pickle.trim())
            .map_err(|e| CryptoError::BadPickle { reason: e.to_string() })?;
        if blob.len() < 12 + 16 {
            return Err(CryptoError::BadPickle { reason: "too short".into() });
        }
        let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
        let plain = cipher
            .decrypt(Nonce::from_slice(&blob[..12]), &blob[12..])
            .map_err(|_| CryptoError::BadPickle { reason: "wrong key or damaged".into() })?;
        let state: State =
            serde_json::from_slice(&plain).map_err(|e| CryptoError::BadPickle { reason: e.to_string() })?;
        if state.v != 1 {
            return Err(CryptoError::BadPickle { reason: format!("unknown version {}", state.v) });
        }
        let sessions = state
            .sessions
            .into_iter()
            .map(|(peer, pickles)| (peer, pickles.into_iter().map(Session::from_pickle).collect()))
            .collect();
        Ok(std::sync::Arc::new(Self {
            inner: Mutex::new(Inner2 {
                account: Account::from_pickle(state.account),
                sealing_secret: StaticSecret::from(state.sealing_secret),
                sessions,
                used_prekeys: state.used_prekeys,
            }),
        }))
    }

    /// Serialises the whole identity, encrypted with ChaCha20-Poly1305 under a
    /// 32-byte key. The app keeps the key in the Keystore and the blob on disk.
    pub fn pickle(&self, key: Vec<u8>) -> Result<String> {
        let key = key_from(&key)?;
        let inner = self.inner.lock().expect("identity mutex poisoned");
        let state = State {
            v: 1,
            account: inner.account.pickle(),
            sealing_secret: inner.sealing_secret.to_bytes(),
            sessions: inner
                .sessions
                .iter()
                .map(|(peer, sessions)| (peer.clone(), sessions.iter().map(Session::pickle).collect()))
                .collect(),
            used_prekeys: inner.used_prekeys.clone(),
        };
        let plain = Zeroizing::new(
            serde_json::to_vec(&state).map_err(|e| CryptoError::BadPickle { reason: e.to_string() })?,
        );
        let mut nonce = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut nonce);
        let cipher = ChaCha20Poly1305::new(Key::from_slice(key.as_slice()));
        let ct = cipher
            .encrypt(Nonce::from_slice(&nonce), plain.as_slice())
            .map_err(|_| CryptoError::Encrypt { reason: "pickle".into() })?;
        let mut out = nonce.to_vec();
        out.extend_from_slice(&ct);
        Ok(B64.encode(out))
    }

    // ── Keys ──────────────────────────────────────────────────────────────

    pub fn ed25519(&self) -> String {
        self.inner.lock().expect("identity mutex poisoned").account.ed25519_key().to_base64()
    }

    pub fn curve25519(&self) -> String {
        self.inner.lock().expect("identity mutex poisoned").account.curve25519_key().to_base64()
    }

    /// The public bundle to publish: identity keys, the signed fallback key and
    /// the one-time keys not yet marked published.
    pub fn public_bundle(&self) -> PublicBundle {
        let inner = self.inner.lock().expect("identity mutex poisoned");
        let curve = inner.account.curve25519_key().to_base64();
        let sealing = B64.encode(XPublicKey::from(&inner.sealing_secret).as_bytes());
        let signature = inner.account.sign(format!("{curve}|{sealing}")).to_base64();
        let fallback = inner.account.fallback_key().into_iter().next().map(|(id, key)| SignedKey {
            id: id.to_base64(),
            key: key.to_base64(),
            signature: inner.account.sign(key.to_base64()).to_base64(),
        });
        let one_time_keys = inner
            .account
            .one_time_keys()
            .into_iter()
            .map(|(id, key)| SignedKey {
                id: id.to_base64(),
                key: key.to_base64(),
                signature: inner.account.sign(key.to_base64()).to_base64(),
            })
            .collect();
        PublicBundle {
            ed25519: inner.account.ed25519_key().to_base64(),
            curve25519: curve,
            sealing,
            signature,
            fallback,
            one_time_keys,
        }
    }

    /// Generates `count` fresh one-time keys (kept until published).
    pub fn generate_one_time_keys(&self, count: u32) {
        self.inner.lock().expect("identity mutex poisoned").account.generate_one_time_keys(count as usize);
    }

    /// Rotates the fallback key. The previous one stays valid until the next rotation.
    pub fn rotate_fallback_key(&self) {
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        inner.account.generate_fallback_key();
    }

    /// Call after the relay has accepted the bundle's keys.
    pub fn mark_keys_published(&self) {
        self.inner.lock().expect("identity mutex poisoned").account.mark_keys_as_published();
    }

    /// Ed25519 signature over `message`, base64.
    pub fn sign(&self, message: Vec<u8>) -> String {
        self.inner.lock().expect("identity mutex poisoned").account.sign(message).to_base64()
    }

    // ── Sessions ──────────────────────────────────────────────────────────

    /// True when at least one session with this peer exists.
    pub fn has_session(&self, peer_curve25519: String) -> bool {
        self.inner
            .lock()
            .expect("identity mutex poisoned")
            .sessions
            .get(&peer_curve25519)
            .is_some_and(|s| !s.is_empty())
    }

    /// Starts an outbound session with `peer` using one of their published keys
    /// (a one-time key or the fallback), after checking its signature and the
    /// signature binding their Curve25519 and sealing keys to their Ed25519 key.
    pub fn start_session(&self, peer: PeerKeys, signed_key: SignedKey) -> Result<()> {
        verify_peer(&peer)?;
        let ed = ed25519_from(&peer.ed25519)?;
        verify_signature(&ed, signed_key.key.as_bytes(), &signed_key.signature)?;
        let their_identity = curve_from(&peer.curve25519)?;
        let their_key = curve_from(&signed_key.key)?;
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let session = inner
            .account
            .create_outbound_session(SessionConfig::version_2(), their_identity, their_key)
            .map_err(|e| CryptoError::Encrypt { reason: e.to_string() })?;
        push_session(&mut inner.sessions, peer.curve25519, session);
        Ok(())
    }

    /// Forgets every session with `peer` (they re-paired, or the app is resetting).
    pub fn drop_sessions(&self, peer_curve25519: String) {
        self.inner.lock().expect("identity mutex poisoned").sessions.remove(&peer_curve25519);
    }

    /// Encrypts `plaintext` for `peer` and seals it for the relay. Requires a session.
    pub fn encrypt(&self, peer: PeerKeys, plaintext: Vec<u8>) -> Result<Vec<u8>> {
        verify_peer(&peer)?;
        let sealing = x25519_from(&peer.sealing)?;
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let me = inner.account.curve25519_key().to_base64();
        let session = inner
            .sessions
            .get_mut(&peer.curve25519)
            .and_then(|s| s.first_mut())
            .ok_or(CryptoError::NoSession)?;
        let message = session
            .encrypt(plaintext)
            .map_err(|e| CryptoError::Encrypt { reason: e.to_string() })?;
        let (message_type, ciphertext) = message.to_parts();
        let inner_bytes = serde_json::to_vec(&Inner { s: me, t: message_type as u8, c: B64.encode(ciphertext) })
            .map_err(|e| CryptoError::Encrypt { reason: e.to_string() })?;
        Ok(seal(&sealing, &inner_bytes))
    }

    /// Opens an envelope addressed to this device. A pre-key message from an
    /// unknown session creates one; the sender's identity key is returned so the
    /// app can check it against a known contact before trusting the content.
    pub fn decrypt(&self, envelope: Vec<u8>) -> Result<Decrypted> {
        let mut inner = self.inner.lock().expect("identity mutex poisoned");
        let inner_bytes = unseal(&inner.sealing_secret, &envelope)?;
        let msg: Inner =
            serde_json::from_slice(&inner_bytes).map_err(|e| CryptoError::Envelope { reason: e.to_string() })?;
        let ciphertext = B64
            .decode(&msg.c)
            .map_err(|e| CryptoError::Envelope { reason: e.to_string() })?;
        let olm = OlmMessage::from_parts(msg.t as usize, &ciphertext)
            .map_err(|e| CryptoError::Envelope { reason: e.to_string() })?;
        let sender_identity = curve_from(&msg.s)?;

        // Existing sessions first, newest first. A session that knows the
        // message's chain but has already spent its key means this exact message
        // was decrypted before: a duplicate, not a broken session.
        let mut spent = false;
        if let Some(sessions) = inner.sessions.get_mut(&msg.s) {
            for session in sessions.iter_mut() {
                match session.decrypt(&olm) {
                    Ok(plaintext) => return Ok(Decrypted { sender_curve25519: msg.s, plaintext, new_session: false }),
                    Err(DecryptionError::MissingMessageKey(_)) => spent = true,
                    Err(_) => {}
                }
            }
        }
        if spent {
            return Err(CryptoError::Duplicate);
        }

        // A pre-key message we have not seen: create the inbound session.
        match olm {
            OlmMessage::PreKey(prekey) => {
                if prekey.identity_key() != sender_identity {
                    return Err(CryptoError::Decrypt { reason: "sender key does not match the pre-key message".into() });
                }
                // A pre-key message that already created one of our sessions is a
                // replay: with a fallback key it would otherwise succeed again,
                // duplicate the session and hand back stale plaintext.
                // The session ids already accepted are remembered even after a
                // session is dropped, so a relay cannot replay an old pre-key
                // message later to have it processed a second time.
                let session_id = prekey.session_id();
                let replayed = inner.used_prekeys.iter().any(|id| *id == session_id)
                    || inner
                        .sessions
                        .get(&msg.s)
                        .is_some_and(|list| list.iter().any(|s| s.session_id() == session_id));
                if replayed {
                    return Err(CryptoError::Duplicate);
                }
                let created = inner
                    .account
                    .create_inbound_session(SessionConfig::version_2(), sender_identity, &prekey)
                    .map_err(|e| CryptoError::DecryptFrom { sender_curve25519: msg.s.clone(), reason: e.to_string() })?;
                push_session(&mut inner.sessions, msg.s.clone(), created.session);
                inner.used_prekeys.push_back(session_id);
                while inner.used_prekeys.len() > MAX_USED_PREKEYS {
                    inner.used_prekeys.pop_front();
                }
                Ok(Decrypted { sender_curve25519: msg.s, plaintext: created.plaintext, new_session: true })
            }
            OlmMessage::Normal(_) => Err(CryptoError::NoSessionFor { sender_curve25519: msg.s }),
        }
    }

    /// A 60-digit safety number for this device and `peer`, the same on both
    /// phones regardless of who computes it: SHA-512 over the two Ed25519 keys
    /// in sorted order, rendered as twelve groups of five digits.
    pub fn safety_number(&self, peer_ed25519: String) -> Result<String> {
        let mine = self.ed25519();
        let peer = ed25519_from(&peer_ed25519)?.to_base64();
        Ok(safety_number_for(&mine, &peer))
    }
}

fn push_session(sessions: &mut HashMap<String, Vec<Session>>, peer: String, session: Session) {
    let list = sessions.entry(peer).or_default();
    list.insert(0, session);
    list.truncate(MAX_SESSIONS_PER_PEER);
}

// ── Free functions ─────────────────────────────────────────────────────────

/// Verifies an Ed25519 signature (base64) over `message` with `ed25519` (base64).
#[uniffi::export]
pub fn verify(ed25519: String, message: Vec<u8>, signature: String) -> bool {
    match ed25519_from(&ed25519) {
        Ok(key) => verify_signature(&key, &message, &signature).is_ok(),
        Err(_) => false,
    }
}

/// The safety number for any two Ed25519 keys (base64), order-independent.
#[uniffi::export]
pub fn safety_number_for(a_ed25519: &str, b_ed25519: &str) -> String {
    let (first, second) = if a_ed25519 <= b_ed25519 { (a_ed25519, b_ed25519) } else { (b_ed25519, a_ed25519) };
    let mut hasher = Sha512::new();
    hasher.update(b"aegis-safety-number-v1");
    hasher.update(first.as_bytes());
    hasher.update(b"|");
    hasher.update(second.as_bytes());
    let digest = hasher.finalize();
    // Twelve groups of five digits, each group from 4 bytes → 0..99999.
    let mut groups = Vec::with_capacity(12);
    for chunk in digest.chunks(4).take(12) {
        let n = u32::from_be_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]) % 100_000;
        groups.push(format!("{n:05}"));
    }
    groups.join(" ")
}

/// A short, URL-safe fingerprint of an Ed25519 key for display and QR codes.
#[uniffi::export]
pub fn fingerprint(ed25519: String) -> String {
    let digest = Sha256::digest(ed25519.as_bytes());
    B64URL.encode(&digest[..12])
}

/// 32 random bytes, base64: the app uses this for the pickle key it wraps in the Keystore.
#[uniffi::export]
pub fn random_key() -> Vec<u8> {
    let mut key = vec![0u8; 32];
    rand::thread_rng().fill_bytes(&mut key);
    key
}

// ── Helpers ────────────────────────────────────────────────────────────────

fn key_from(key: &[u8]) -> Result<[u8; 32]> {
    key.try_into().map_err(|_| CryptoError::BadKey { reason: "pickle key must be 32 bytes".into() })
}

fn curve_from(b64: &str) -> Result<Curve25519PublicKey> {
    Curve25519PublicKey::from_base64(b64).map_err(|e| CryptoError::BadKey { reason: e.to_string() })
}

fn ed25519_from(b64: &str) -> Result<Ed25519PublicKey> {
    Ed25519PublicKey::from_base64(b64).map_err(|e| CryptoError::BadKey { reason: e.to_string() })
}

fn x25519_from(b64: &str) -> Result<XPublicKey> {
    let bytes = B64.decode(b64).map_err(|e| CryptoError::BadKey { reason: e.to_string() })?;
    let arr: [u8; 32] =
        bytes.try_into().map_err(|_| CryptoError::BadKey { reason: "sealing key must be 32 bytes".into() })?;
    Ok(XPublicKey::from(arr))
}

fn verify_signature(key: &Ed25519PublicKey, message: &[u8], signature_b64: &str) -> Result<()> {
    let signature =
        Ed25519Signature::from_base64(signature_b64).map_err(|_| CryptoError::BadSignature)?;
    key.verify(message, &signature).map_err(|_| CryptoError::BadSignature)
}

/// Checks that a peer's Curve25519 and sealing keys are signed by their Ed25519 key.
fn verify_peer(peer: &PeerKeys) -> Result<()> {
    let ed = ed25519_from(&peer.ed25519)?;
    verify_signature(&ed, format!("{}|{}", peer.curve25519, peer.sealing).as_bytes(), &peer.signature)
}

// ── Tests ──────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn peer_keys(id: &Identity) -> PeerKeys {
        let b = id.public_bundle();
        PeerKeys { ed25519: b.ed25519, curve25519: b.curve25519, sealing: b.sealing, signature: b.signature }
    }

    #[test]
    fn round_trip_between_two_identities() {
        let alice = Identity::create();
        let bob = Identity::create();
        bob.generate_one_time_keys(3);
        let bob_bundle = bob.public_bundle();
        assert_eq!(bob_bundle.one_time_keys.len(), 3);
        bob.mark_keys_published();
        assert!(bob.public_bundle().one_time_keys.is_empty());

        // Alice starts a session with one of Bob's one-time keys.
        alice.start_session(peer_keys(&bob), bob_bundle.one_time_keys[0].clone()).unwrap();
        assert!(alice.has_session(bob.curve25519()));

        // First message is a pre-key message; Bob has no session yet.
        let env1 = alice.encrypt(peer_keys(&bob), b"hello bob".to_vec()).unwrap();
        let d1 = bob.decrypt(env1).unwrap();
        assert_eq!(d1.plaintext, b"hello bob");
        assert!(d1.new_session);
        assert_eq!(d1.sender_curve25519, alice.curve25519());

        // Bob replies over the same session; Alice reads it with hers.
        let env2 = bob.encrypt(peer_keys(&alice), b"hi alice".to_vec()).unwrap();
        let d2 = alice.decrypt(env2).unwrap();
        assert_eq!(d2.plaintext, b"hi alice");
        assert!(!d2.new_session);

        // Many messages in a row ratchet fine.
        for i in 0..20 {
            let env = alice.encrypt(peer_keys(&bob), format!("msg {i}").into_bytes()).unwrap();
            assert_eq!(bob.decrypt(env).unwrap().plaintext, format!("msg {i}").into_bytes());
        }
    }

    #[test]
    fn fallback_key_works_when_one_time_keys_are_gone() {
        let alice = Identity::create();
        let bob = Identity::create();
        let fallback = bob.public_bundle().fallback.expect("fallback key present");
        alice.start_session(peer_keys(&bob), fallback).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"via fallback".to_vec()).unwrap();
        assert_eq!(bob.decrypt(env).unwrap().plaintext, b"via fallback");
    }

    #[test]
    fn replayed_prekey_message_is_rejected() {
        let alice = Identity::create();
        let bob = Identity::create();
        // The fallback key stays on the account after use, so without the
        // replay check a second delivery would create a duplicate session.
        let fallback = bob.public_bundle().fallback.expect("fallback key present");
        alice.start_session(peer_keys(&bob), fallback).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"first".to_vec()).unwrap();
        let first = bob.decrypt(env.clone()).unwrap();
        assert!(first.new_session);
        let again = bob.decrypt(env);
        assert!(matches!(again, Err(CryptoError::Duplicate)), "replay accepted: {again:?}");
        assert_eq!(bob.inner.lock().unwrap().sessions[&alice.curve25519()].len(), 1);
        // The session itself keeps working after the rejected replay.
        let env2 = alice.encrypt(peer_keys(&bob), b"second".to_vec()).unwrap();
        assert_eq!(bob.decrypt(env2).unwrap().plaintext, b"second");
    }

    #[test]
    fn prekey_on_a_lost_one_time_key_names_its_sender() {
        let alice = Identity::create();
        let bob = Identity::create();
        let key = random_key();
        // Bob's state before he generated the key Alice is about to use: the same
        // identity, but without that one-time key (a restore from an older pickle).
        let bob_before = Identity::restore(bob.pickle(key.clone()).unwrap(), key).unwrap();
        bob.generate_one_time_keys(1);
        let otk = bob.public_bundle().one_time_keys[0].clone();
        alice.start_session(peer_keys(&bob), otk).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"hello".to_vec()).unwrap();
        match bob_before.decrypt(env) {
            Err(CryptoError::DecryptFrom { sender_curve25519, .. }) => assert_eq!(sender_curve25519, alice.curve25519()),
            other => panic!("expected DecryptFrom, got {other:?}"),
        }
    }

    #[test]
    fn a_redelivered_normal_message_is_a_duplicate_not_a_broken_session() {
        let alice = Identity::create();
        let bob = Identity::create();
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        bob.decrypt(alice.encrypt(peer_keys(&bob), b"hi".to_vec()).unwrap()).unwrap();
        alice.decrypt(bob.encrypt(peer_keys(&alice), b"hello".to_vec()).unwrap()).unwrap();
        // A normal (post-pre-key) message, delivered twice.
        let env = alice.encrypt(peer_keys(&bob), b"once".to_vec()).unwrap();
        assert_eq!(bob.decrypt(env.clone()).unwrap().plaintext, b"once");
        assert!(matches!(bob.decrypt(env), Err(CryptoError::Duplicate)));
        // And the session is unharmed.
        let next = alice.encrypt(peer_keys(&bob), b"next".to_vec()).unwrap();
        assert_eq!(bob.decrypt(next).unwrap().plaintext, b"next");
    }

    #[test]
    fn a_replayed_prekey_is_refused_even_after_the_session_is_dropped() {
        let alice = Identity::create();
        let bob = Identity::create();
        let key = random_key();
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"first".to_vec()).unwrap();
        bob.decrypt(env.clone()).unwrap();
        bob.drop_sessions(alice.curve25519());
        // Survives a pickle round trip too.
        let bob2 = Identity::restore(bob.pickle(key.clone()).unwrap(), key).unwrap();
        assert!(matches!(bob2.decrypt(env), Err(CryptoError::Duplicate)));
    }

    #[test]
    fn older_pickles_without_the_replay_list_still_restore() {
        // Decrypt a current pickle, take the new field out and re-encrypt it:
        // exactly what a phone on an earlier version has on disk.
        let alice = Identity::create();
        let key = random_key();
        let blob = B64.decode(alice.pickle(key.clone()).unwrap()).unwrap();
        let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
        let plain = cipher.decrypt(Nonce::from_slice(&blob[..12]), &blob[12..]).unwrap();
        let mut json: serde_json::Value = serde_json::from_slice(&plain).unwrap();
        assert!(json.as_object_mut().unwrap().remove("used_prekeys").is_some());
        let legacy = serde_json::to_vec(&json).unwrap();
        let nonce = [7u8; 12];
        let mut out = nonce.to_vec();
        out.extend(cipher.encrypt(Nonce::from_slice(&nonce), legacy.as_slice()).unwrap());
        let restored = Identity::restore(B64.encode(out), key).unwrap();
        assert_eq!(restored.ed25519(), alice.ed25519());
    }

    #[test]
    fn envelope_is_opaque_to_third_parties() {
        let alice = Identity::create();
        let bob = Identity::create();
        let eve = Identity::create();
        let key = bob.public_bundle().fallback.unwrap();
        alice.start_session(peer_keys(&bob), key).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"secret".to_vec()).unwrap();
        // The sealed envelope carries nothing but a version byte and an ephemeral key in the clear.
        assert!(!env.windows(6).any(|w| w == b"secret"));
        assert!(eve.decrypt(env.clone()).is_err());
        // Tampering is detected.
        let mut bad = env.clone();
        let last = bad.len() - 1;
        bad[last] ^= 1;
        assert!(bob.decrypt(bad).is_err());
    }

    #[test]
    fn forged_keys_are_rejected() {
        let alice = Identity::create();
        let bob = Identity::create();
        let mallory = Identity::create();
        let mut peer = peer_keys(&bob);
        // Swap in Mallory's sealing key: the binding signature no longer matches.
        peer.sealing = mallory.public_bundle().sealing;
        let key = bob.public_bundle().fallback.unwrap();
        assert!(matches!(alice.start_session(peer, key), Err(CryptoError::BadSignature)));
        // A one-time key signed by someone else is refused too.
        let mut key = bob.public_bundle().fallback.unwrap();
        key.signature = mallory.sign(key.key.as_bytes().to_vec());
        assert!(matches!(alice.start_session(peer_keys(&bob), key), Err(CryptoError::BadSignature)));
    }

    #[test]
    fn pickle_restores_sessions() {
        let alice = Identity::create();
        let bob = Identity::create();
        let key = random_key();
        alice.start_session(peer_keys(&bob), bob.public_bundle().fallback.unwrap()).unwrap();
        let env = alice.encrypt(peer_keys(&bob), b"before pickle".to_vec()).unwrap();
        bob.decrypt(env).unwrap();

        let alice2 = Identity::restore(alice.pickle(key.clone()).unwrap(), key.clone()).unwrap();
        let bob2 = Identity::restore(bob.pickle(key.clone()).unwrap(), key.clone()).unwrap();
        assert_eq!(alice2.ed25519(), alice.ed25519());
        assert!(alice2.has_session(bob.curve25519()));
        let env = alice2.encrypt(peer_keys(&bob2), b"after pickle".to_vec()).unwrap();
        assert_eq!(bob2.decrypt(env).unwrap().plaintext, b"after pickle");

        // Wrong key: refused, nothing leaks.
        let other = random_key();
        assert!(matches!(Identity::restore(alice.pickle(key).unwrap(), other), Err(CryptoError::BadPickle { .. })));
    }

    #[test]
    fn safety_number_is_symmetric_and_stable() {
        let alice = Identity::create();
        let bob = Identity::create();
        let a = alice.safety_number(bob.ed25519()).unwrap();
        let b = bob.safety_number(alice.ed25519()).unwrap();
        assert_eq!(a, b);
        assert_eq!(a.len(), 12 * 5 + 11);
        assert_eq!(safety_number_for(&alice.ed25519(), &bob.ed25519()), a);
        assert_ne!(a, Identity::create().safety_number(bob.ed25519()).unwrap());
    }

    #[test]
    fn verify_checks_signatures() {
        let alice = Identity::create();
        let sig = alice.sign(b"bundle".to_vec());
        assert!(verify(alice.ed25519(), b"bundle".to_vec(), sig.clone()));
        assert!(!verify(alice.ed25519(), b"other".to_vec(), sig));
        assert!(!verify("not a key".into(), b"bundle".to_vec(), "x".into()));
    }
}
