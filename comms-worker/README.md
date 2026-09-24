# Aegis comms relay

A Cloudflare Worker that stores and forwards end-to-end encrypted envelopes
between Aegis apps. It is the only server in the design, and it is built to
know as little as possible.

**Aegis numbers are not phone numbers.** Each Aegis identity gets a random
nine-digit Aegis number from this relay at registration. It works only between
Aegis apps paired with the same relay. It cannot call or text a phone, and a
phone cannot reach it. What it buys is a handle you can give someone without
giving them your real number or your identity key.

## What the relay sees

- The public keys of each registered identity (they are public by design).
- For each envelope: the recipient's Aegis number, a size and a timestamp, and,
  at the moment it is sent, the sender's Aegis number, because the send request
  is signed by the sender. The relay does not store the sender with the
  envelope, and a queued envelope names only its recipient, but a relay
  operator who logs requests can see who sent to whom and when. Never the
  content: envelopes are sealed to the recipient's sealing key with an
  anonymous box, and inside that sits an Olm double-ratchet message the relay
  could not read even if it opened the box.
- The UnifiedPush endpoint an owner registers, if any, which it POSTs the word
  "wake" to when a live socket is not connected.

Authentication is by Ed25519 signature with the same identity key contacts
verify. There are no passwords or tokens to leak.

## One-time setup

```sh
cd comms-worker
npm install
npx wrangler login              # or set CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID
npx wrangler secret put ENROLL_SECRET   # a long random string typed into Aegis once
npm run deploy                  # prints the Worker URL
```

In Aegis, open the COMMS tab, enter the Worker URL and the enrollment secret.
The app generates its identity on the phone, registers it, and shows the Aegis
number it was given. Two people pair by scanning each other's QR code in
person, or by typing a listed Aegis number and then comparing safety numbers.

### Inviting people

Only the relay's owner needs the URL and the enrollment secret. Everyone else
joins by invite: in Aegis, COMMS → INVITE makes a one-time invite. Someone
standing next to you scans its QR code from COMMS setup (SCAN INVITE); someone
far away gets a link, `https://<relay>/i#…`, which opens a page with the Aegis
download and an OPEN IN AEGIS button. The invite registers exactly one phone,
expires after seven days, and never reveals the enrollment secret. The new
phone starts with the inviter as a verified contact, since the invite carries
the inviter's keys, and sends them a first message. The part after `#` never
reaches the relay; the page reads it in the browser. Each identity can make
20 invites a day.

### Calls (optional TURN)

Calls are WebRTC audio between the two phones, keyed by DTLS fingerprints that
travel inside the encrypted envelopes, so the relay never sees or handles the
media keys. Without TURN, calls use STUN only and connect when both phones can
reach each other directly (most home and mobile networks, not all). For calls
across strict NATs, create a TURN key in the Cloudflare dashboard under
Realtime → TURN and give it to the Worker:

```sh
npx wrangler secret put TURN_KEY_ID
npx wrangler secret put TURN_KEY_API_TOKEN
```

The Worker then mints short-lived TURN credentials for `GET /v1/turn`. A TURN
server forwards encrypted packets it cannot decrypt.

## API

Signed requests carry `X-Aegis-Number`, `X-Aegis-Ts` (epoch millis, ±5 min),
`X-Aegis-Nonce` (single use) and `X-Aegis-Sig`, an Ed25519 signature over
`number\nts\nnonce\nMETHOD\npath?query\nsha256hex(body)`.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/v1/register` | Body signed by the new identity's key, with `secret` or a one-time `invite`; allocates an Aegis number |
| POST | `/v1/invites` | Makes a one-time invite: `{code, expiresAt}` (seven days) |
| GET | `/i` | Invite landing page (unsigned); the invite is in the URL fragment |
| GET | `/v1/me` | Own profile and one-time-key count |
| DELETE | `/v1/me` | Wipe the mailbox |
| PUT | `/v1/keys` | Replenish one-time keys; rotate the fallback key |
| PUT | `/v1/listed` | Whether the number can be looked up |
| PUT | `/v1/push` | UnifiedPush endpoint to wake this device, or null |
| GET | `/v1/bundle/:number` | A contact's keys and one session key (unlisted numbers need `?pin=<fingerprint>`) |
| POST | `/v1/send` | `{to, envelope}`: queue a sealed envelope for a contact |
| GET | `/v1/inbox` | Waiting envelopes |
| POST | `/v1/ack` | Delete envelopes the app has stored |
| GET | `/v1/ws` | Live delivery over a hibernatable WebSocket: `envelope` frames (acked with `{"type":"ack","ids":[…]}`), a `ready` frame after the backlog, a `hb` heartbeat every two minutes, and `pong` for a client's `{"type":"ping"}` |
| GET | `/v1/turn` | ICE servers for a call: STUN, plus short-lived TURN credentials when a TURN key is configured |

Each Aegis number is a Durable Object holding the keys, the queue (up to 2000
envelopes of 64 KiB, kept 30 days) and the live sockets.

## Development

`npm run typecheck` runs the TypeScript compiler; `npx wrangler deploy --dry-run`
bundles the Worker. `npm run dev` runs it locally with a local Durable Object.
