import { parseSigned, verifyEd25519 } from "./auth";
import { HttpError, json, normaliseNumber, randomNumber, requireSecret, timingSafeEqual, type Env } from "./env";
import { INVITE_TTL_MS, Mailbox, type CoinTx, type Profile, type SignedKey } from "./mailbox";
import { invitePage } from "./invite-page";

export { Mailbox };

/**
 * Aegis comms relay: store-and-forward for end-to-end encrypted envelopes
 * between Aegis apps, addressed by Aegis number.
 *
 * An Aegis number is not a phone number. It is a random nine-digit ID this
 * relay hands out at registration, and it works only between Aegis apps
 * paired with this relay.
 *
 *   POST   /v1/register        {secret | invite, ed25519, curve25519, sealing, signature, fallback, oneTimeKeys, listed}
 *                              signed by ed25519 (X-Aegis-Sig over the body) → {number}
 *   GET    /i                                              → invite page; the invite itself is in the URL fragment,
 *                                                            which browsers never send to the relay
 * Signed (see auth.ts):
 *   GET    /v1/me                                          → profile + key counts
 *   PUT    /v1/keys            {oneTimeKeys, fallback?}    → replenish
 *   PUT    /v1/listed          {listed}
 *   PUT    /v1/push            {endpoint|null}             → UnifiedPush endpoint to wake this device
 *   DELETE /v1/me                                          → wipe the mailbox
 *   POST   /v1/invites                                     → {code, expiresAt}: one registration, no secret, 7 days
 *   GET    /v1/bundle/:number                              → a contact's keys + one session key (listed only)
 *   GET    /v1/bundle/:number?pin=<ed25519 fingerprint>    → also unlisted, when the caller has the key from a QR
 *   GET    /v1/identity/:number[?pin=]                     → the same keys without claiming a one-time key
 *   POST   /v1/send            {to, envelope}              → queue for a contact
 *   GET    /v1/inbox                                       → waiting envelopes
 *   POST   /v1/ack             {ids}
 *   GET    /v1/ws                                          → live delivery (hibernatable WebSocket)
 *   GET    /v1/turn                                        → ICE servers for a call
 * AegisCoin (each relay is its own coin community; balances live in the mailboxes):
 *   GET    /v1/coin/balance                                → {balance, symbol: "AC"}
 *   POST   /v1/coin/pay        {to, amount, note?}         → {txid, balance, ok}: moves coins to a contact
 *   GET    /v1/coin/history                                → {txs: [...]}, newest first
 *   POST   /v1/admin/coin/mint {to, amount}                → {ok, balance}; X-Admin-Secret must match ADMIN_SECRET
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await route(request, env);
    } catch (e) {
      if (e instanceof HttpError) {
        const res = json({ error: e.message }, e.status);
        if (e.retryAfter !== undefined) res.headers.set("retry-after", String(e.retryAfter));
        return res;
      }
      // A Durable Object being restarted (a deploy, an overloaded object) is
      // temporary: tell the client to try again rather than fail for good.
      const err = e as { retryable?: boolean; overloaded?: boolean };
      if (err?.retryable || err?.overloaded) {
        const res = json({ error: "Relay busy; try again" }, 503);
        res.headers.set("retry-after", "2");
        return res;
      }
      console.error(e);
      return json({ error: "Internal error" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";

  if (request.method === "GET" && path === "/") {
    return json({ service: "aegis-comms", version: 2, ok: true, note: "Aegis numbers are not phone numbers." });
  }

  const bodyText = request.method === "GET" || request.method === "HEAD" ? "" : await request.text();

  if (request.method === "GET" && path === "/i") {
    return new Response(invitePage(), {
      headers: {
        "content-type": "text/html; charset=utf-8",
        "cache-control": "public, max-age=3600",
        "referrer-policy": "no-referrer",
        "x-content-type-options": "nosniff",
      },
    });
  }

  if (path === "/v1/register") {
    if (request.method !== "POST") throw new HttpError(405, "POST only");
    // A few attempts a minute per address: the enrollment secret cannot be guessed by brute force.
    const guard = env.MAILBOX.get(env.MAILBOX.idFromName(`register-guard:${request.headers.get("cf-connecting-ip") ?? "unknown"}`));
    const allowed = await guard.rateOk("register", Mailbox.REGISTER_LIMIT);
    if (!allowed.ok) throw new HttpError(429, "Too many registration attempts; try again in a minute", allowed.retryAfter);
    return register(env, request, bodyText);
  }

  if (!path.startsWith("/v1/")) throw new HttpError(404, "Not found");

  // ── Authenticated ─────────────────────────────────────────────────────
  const signed = await parseSigned(request, bodyText);
  const me = env.MAILBOX.get(env.MAILBOX.idFromName(signed.number));
  const profile = await me.authenticate(signed.payload, signed.signature, signed.nonce, signed.ts);
  if (!profile) throw new HttpError(401, "Signature rejected");

  if (path === "/v1/me" && request.method === "GET") {
    return json({ profile: publicProfile(profile), oneTimeKeys: await me.oneTimeKeyCount() });
  }

  if (path === "/v1/me" && request.method === "DELETE") {
    await me.wipe();
    return json({ ok: true });
  }

  if (path === "/v1/invites" && request.method === "POST") {
    const allowed = await me.rateOk("invite", Mailbox.INVITE_LIMIT, Mailbox.INVITE_BURST);
    if (!allowed.ok) throw new HttpError(429, "Too many invites today; try again later", allowed.retryAfter);
    const code = randomInviteCode();
    const expiresAt = Date.now() + INVITE_TTL_MS;
    await env.MAILBOX.get(env.MAILBOX.idFromName(`invite:${code}`)).createInvite(profile.number, expiresAt);
    return json({ code, expiresAt }, 201);
  }

  if (path === "/v1/keys" && request.method === "PUT") {
    const body = parseJson<{ oneTimeKeys?: unknown; fallback?: unknown }>(bodyText);
    const oneTimeKeys = await checkedKeys(profile.ed25519, body.oneTimeKeys);
    const fallback = body.fallback === undefined || body.fallback === null ? null : (await checkedKeys(profile.ed25519, [body.fallback]))[0] ?? null;
    return json(await me.putKeys(oneTimeKeys, fallback));
  }

  if (path === "/v1/listed" && request.method === "PUT") {
    const body = parseJson<{ listed?: unknown }>(bodyText);
    if (typeof body.listed !== "boolean") throw new HttpError(400, "listed must be a boolean");
    await me.setListed(body.listed);
    return json({ ok: true, listed: body.listed });
  }

  if (path === "/v1/push" && request.method === "PUT") {
    const body = parseJson<{ endpoint?: unknown }>(bodyText);
    const endpoint = body.endpoint;
    if (endpoint !== null && endpoint !== undefined) {
      if (typeof endpoint !== "string" || !/^https:\/\/[^\s]{8,2048}$/.test(endpoint)) {
        throw new HttpError(400, "endpoint must be an https URL");
      }
    }
    await me.setPush(typeof endpoint === "string" ? endpoint : null);
    return json({ ok: true });
  }

  const bundleMatch = /^\/v1\/bundle\/([0-9]{9})$/.exec(path);
  if (bundleMatch && bundleMatch[1] && request.method === "GET") {
    const allowed = await me.rateOk("lookup", Mailbox.LOOKUP_LIMIT);
    if (!allowed.ok) throw new HttpError(429, "Too many lookups; try again in a minute", allowed.retryAfter);
    const target = normaliseNumber(bundleMatch[1]);
    if (!target) throw new HttpError(400, "Bad number");
    const pin = url.searchParams.get("pin");
    const them = env.MAILBOX.get(env.MAILBOX.idFromName(target));
    // Unlisted numbers are only handed out to a caller who already holds the
    // owner's identity key (from a QR scan) and proves it with its fingerprint.
    // The pin is checked before any key is claimed, so a wrong pin neither
    // reveals the bundle nor consumes a one-time key.
    let bundle = null;
    if (pin === null) {
      bundle = await them.bundle(true);
    } else {
      const p = await them.profile();
      if (p && timingSafeEqual(await fingerprint(p.ed25519), pin)) bundle = await them.bundle(false);
    }
    if (!bundle) throw new HttpError(404, "No such Aegis number, or it is unlisted");
    return json({ bundle });
  }

  const identityMatch = /^\/v1\/identity\/([0-9]{9})$/.exec(path);
  if (identityMatch && identityMatch[1] && request.method === "GET") {
    // Who holds a number, without claiming one of their one-time keys: used to
    // confirm the sender of a first message. Same listed/pin rules as bundles.
    const allowed = await me.rateOk("lookup", Mailbox.LOOKUP_LIMIT);
    if (!allowed.ok) throw new HttpError(429, "Too many lookups; try again in a minute", allowed.retryAfter);
    const target = normaliseNumber(identityMatch[1]);
    if (!target) throw new HttpError(400, "Bad number");
    const pin = url.searchParams.get("pin");
    const them = env.MAILBOX.get(env.MAILBOX.idFromName(target));
    let identity = null;
    if (pin === null) {
      identity = await them.identity(true);
    } else {
      const p = await them.profile();
      if (p && timingSafeEqual(await fingerprint(p.ed25519), pin)) identity = await them.identity(false);
    }
    if (!identity) throw new HttpError(404, "No such Aegis number, or it is unlisted");
    return json({ identity });
  }

  if (path === "/v1/send" && request.method === "POST") {
    // Per-sender cap, so one registered identity cannot fill another's mailbox.
    const allowed = await me.rateOk("send", Mailbox.SEND_LIMIT, Mailbox.SEND_BURST);
    if (!allowed.ok) throw new HttpError(429, "Too many messages; try again shortly", allowed.retryAfter);
    const body = parseJson<{ to?: unknown; envelope?: unknown }>(bodyText);
    const to = typeof body.to === "string" ? normaliseNumber(body.to) : null;
    if (!to) throw new HttpError(400, "to must be an Aegis number");
    if (typeof body.envelope !== "string" || body.envelope.length === 0 || !/^[A-Za-z0-9+/=]+$/.test(body.envelope)) {
      throw new HttpError(400, "envelope must be base64");
    }
    const them = env.MAILBOX.get(env.MAILBOX.idFromName(to));
    const result = await them.enqueue(body.envelope);
    if (!result.ok) throw new HttpError(result.reason === "no such number" ? 404 : 413, result.reason);
    return json({ id: result.id }, 202);
  }

  if (path === "/v1/inbox" && request.method === "GET") {
    return json({ envelopes: await me.inbox() });
  }

  if (path === "/v1/ack" && request.method === "POST") {
    const body = parseJson<{ ids?: unknown }>(bodyText);
    if (!Array.isArray(body.ids)) throw new HttpError(400, "ids must be an array");
    const ids = body.ids.filter((x): x is string => typeof x === "string").slice(0, 500);
    return json({ removed: await me.ack(ids) });
  }

  if (path === "/v1/ws" && request.method === "GET") {
    return me.fetch(request);
  }

  if (path === "/v1/turn" && request.method === "GET") {
    const allowed = await me.rateOk("turn", Mailbox.TURN_LIMIT);
    if (!allowed.ok) throw new HttpError(429, "Too many requests; try again in a minute", allowed.retryAfter);
    const servers = await iceServers(env);
    // "turn" tells the app whether calls can be relayed when the phones cannot reach each other directly.
    return json({ iceServers: servers, turn: servers.some((s) => s.urls.some((u) => u.startsWith("turn"))) });
  }

  // ── AegisCoin ─────────────────────────────────────────────────────────
  if (path === "/v1/coin/balance" && request.method === "GET") {
    return json({ balance: await me.getBalance(), symbol: COIN_SYMBOL });
  }

  if (path === "/v1/coin/pay" && request.method === "POST") {
    const allowed = await me.rateOk("pay", Mailbox.PAY_LIMIT);
    if (!allowed.ok) throw new HttpError(429, "Too many payments; try again in a minute", allowed.retryAfter);
    const body = parseJson<{ to?: unknown; amount?: unknown; note?: unknown }>(bodyText);
    const to = typeof body.to === "string" ? normaliseNumber(body.to) : null;
    if (!to) throw new HttpError(400, "to must be an Aegis number");
    if (to === profile.number) throw new HttpError(400, "You cannot pay yourself");
    const amount = coinAmount(body.amount);
    const note = coinNote(body.note);
    const them = env.MAILBOX.get(env.MAILBOX.idFromName(to));
    if (!(await them.profile())) throw new HttpError(404, "No such Aegis number on this relay");
    const txid = crypto.randomUUID();
    const debited = await me.debit(to, amount, txid, note);
    if (!debited.ok) throw new HttpError(400, debited.reason ?? "Payment refused");
    try {
      await them.credit(profile.number, amount, txid, note);
    } catch (e) {
      // The sender has paid and the recipient has not been credited: give the
      // coins back. If even that fails the error is logged with the txid, so
      // the operator can put it right by hand.
      try {
        await me.credit(to, amount, `${txid}:refund`, "Refund: payment could not be delivered");
      } catch (refund) {
        console.error(`coin payment ${txid} from ${profile.number} to ${to} for ${amount}: credit failed and so did the refund`, e, refund);
      }
      throw e;
    }
    return json({ txid, balance: debited.balance, ok: true });
  }

  if (path === "/v1/coin/history" && request.method === "GET") {
    const raw = Number(url.searchParams.get("limit") ?? "");
    const limit = Number.isFinite(raw) && raw > 0 ? Math.min(100, Math.floor(raw)) : 50;
    const txs: CoinTx[] = await me.txHistory(limit);
    return json({ txs });
  }

  if (path === "/v1/admin/coin/mint" && request.method === "POST") {
    const secret = requireSecret(env, "ADMIN_SECRET");
    const given = request.headers.get("x-admin-secret") ?? "";
    if (!timingSafeEqual(given, secret)) throw new HttpError(403, "Admin secret rejected");
    const body = parseJson<{ to?: unknown; amount?: unknown }>(bodyText);
    const to = typeof body.to === "string" ? normaliseNumber(body.to) : null;
    if (!to) throw new HttpError(400, "to must be an Aegis number");
    const amount = coinAmount(body.amount);
    const them = env.MAILBOX.get(env.MAILBOX.idFromName(to));
    if (!(await them.profile())) throw new HttpError(404, "No such Aegis number on this relay");
    const balance = await them.credit("mint", amount, crypto.randomUUID(), `Minted by ${profile.number}`);
    return json({ ok: true, balance });
  }

  throw new HttpError(404, "Not found");
}

const COIN_SYMBOL = "AC";
const COIN_NOTE_MAX = 140;

/** A whole number of coins between 1 and the single-transfer cap, or a 400. */
function coinAmount(raw: unknown): number {
  if (typeof raw !== "number" || !Number.isInteger(raw) || raw < 1 || raw > Mailbox.MAX_SINGLE_TRANSFER) {
    throw new HttpError(400, `amount must be a whole number from 1 to ${Mailbox.MAX_SINGLE_TRANSFER}`);
  }
  return raw;
}

/** The optional note on a payment: a short string, or nothing. */
function coinNote(raw: unknown): string {
  if (raw === undefined || raw === null) return "";
  if (typeof raw !== "string") throw new HttpError(400, "note must be a string");
  if (raw.length > COIN_NOTE_MAX) throw new HttpError(400, `note must be at most ${COIN_NOTE_MAX} characters`);
  return raw;
}

/**
 * Registration. The body is signed by the new identity's Ed25519 key (proof of
 * possession), carries the enrollment secret, and the relay allocates a fresh
 * Aegis number. The curve25519 and sealing keys must be signed by the Ed25519
 * key, and every one-time key must be too: the relay checks so it never stores
 * a bundle a contact would reject.
 */
async function register(env: Env, request: Request, bodyText: string): Promise<Response> {
  const body = parseJson<{
    secret?: unknown;
    invite?: unknown;
    ed25519?: unknown;
    curve25519?: unknown;
    sealing?: unknown;
    signature?: unknown;
    fallback?: unknown;
    oneTimeKeys?: unknown;
    listed?: unknown;
  }>(bodyText);
  // Either the relay's enrollment secret, or an invite a registered phone made.
  const invite = typeof body.invite === "string" ? body.invite : null;
  if (invite !== null) {
    if (!INVITE_CODE_RE.test(invite)) throw new HttpError(403, "That invite code is not valid");
  } else {
    const secret = requireSecret(env, "ENROLL_SECRET");
    if (typeof body.secret !== "string" || !timingSafeEqual(body.secret, secret)) throw new HttpError(403, "Enrollment code rejected");
  }
  for (const field of ["ed25519", "curve25519", "sealing", "signature"] as const) {
    if (typeof body[field] !== "string" || (body[field] as string).length === 0) throw new HttpError(400, `${field} required`);
  }
  const ed25519 = body.ed25519 as string;
  const curve25519 = body.curve25519 as string;
  const sealing = body.sealing as string;
  const signature = body.signature as string;

  const bodySig = request.headers.get("x-aegis-sig") ?? "";
  if (!(await verifyEd25519(ed25519, bodyText, bodySig))) throw new HttpError(401, "Body signature does not match ed25519");
  if (!(await verifyEd25519(ed25519, `${curve25519}|${sealing}`, signature))) throw new HttpError(400, "Key binding signature invalid");
  const fallback = body.fallback === undefined || body.fallback === null ? null : (await checkedKeys(ed25519, [body.fallback]))[0] ?? null;
  const oneTimeKeys = await checkedKeys(ed25519, body.oneTimeKeys ?? []);
  const listed = body.listed !== false;

  // Used up only once everything else about the request has checked out.
  const inviteBox = invite === null ? null : env.MAILBOX.get(env.MAILBOX.idFromName(`invite:${invite}`));
  if (inviteBox) {
    const redeemed = await inviteBox.redeemInvite();
    if (!redeemed.ok) throw new HttpError(403, redeemed.reason);
  }

  for (let attempt = 0; attempt < 8; attempt++) {
    const number = randomNumber();
    const mailbox = env.MAILBOX.get(env.MAILBOX.idFromName(number));
    const now = Date.now();
    const profile: Profile = { number, ed25519, curve25519, sealing, signature, fallback, listed, createdAt: now, lastSeen: now };
    if (await mailbox.claim(profile, oneTimeKeys)) {
      // A new member of this relay's coin community starts with what the
      // operator set; nothing when they set nothing.
      const initial = parseInt(env.COIN_INITIAL_BALANCE ?? "0", 10);
      if (Number.isInteger(initial) && initial > 0) await mailbox.setInitialBalance(initial);
      return json({ number, profile: publicProfile(profile), oneTimeKeys: oneTimeKeys.length }, 201);
    }
  }
  if (inviteBox) await inviteBox.releaseInvite();
  throw new HttpError(503, "Could not allocate a number; try again");
}

/** 128 random bits, base64url: 22 characters. */
const INVITE_CODE_RE = /^[A-Za-z0-9_-]{22}$/;

function randomInviteCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Validates a list of signed keys against the owner's Ed25519 key. */
async function checkedKeys(ed25519: string, raw: unknown): Promise<SignedKey[]> {
  if (!Array.isArray(raw)) throw new HttpError(400, "keys must be an array");
  if (raw.length > 100) throw new HttpError(400, "at most 100 keys per request");
  const out: SignedKey[] = [];
  for (const item of raw) {
    const k = item as Partial<SignedKey>;
    if (typeof k.id !== "string" || typeof k.key !== "string" || typeof k.signature !== "string") {
      throw new HttpError(400, "each key needs id, key and signature");
    }
    if (!(await verifyEd25519(ed25519, k.key, k.signature))) throw new HttpError(400, `key ${k.id} is not signed by ed25519`);
    out.push({ id: k.id, key: k.key, signature: k.signature });
  }
  return out;
}

function publicProfile(p: Profile) {
  return {
    number: p.number,
    ed25519: p.ed25519,
    curve25519: p.curve25519,
    sealing: p.sealing,
    signature: p.signature,
    listed: p.listed,
    createdAt: p.createdAt,
  };
}

/** An ICE server entry as WebRTC clients consume it. */
interface IceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

const STUN_ONLY: IceServer[] = [{ urls: ["stun:stun.cloudflare.com:3478"] }];
const TURN_TTL_S = 24 * 60 * 60;
/** Credentials are minted for 24 h and handed out for at most 6 h, so a call never outlives them. */
const TURN_CACHE_MS = 6 * 60 * 60_000;

let turnCache: { servers: IceServer[]; expires: number } | null = null;

/**
 * ICE servers for a call. With a Cloudflare Realtime TURN key configured,
 * short-lived TURN credentials are minted and cached in this isolate; without
 * one, calls get STUN only and connect when both phones can reach each other
 * directly. Media is DTLS-SRTP between the two phones either way: a TURN
 * server forwards packets it cannot decrypt.
 */
async function iceServers(env: Env): Promise<IceServer[]> {
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) return STUN_ONLY;
  if (turnCache && turnCache.expires > Date.now()) return turnCache.servers;
  let res: Response;
  try {
    res = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`, {
      method: "POST",
      headers: { authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`, "content-type": "application/json" },
      body: JSON.stringify({ ttl: TURN_TTL_S }),
    });
  } catch (e) {
    console.error("TURN credential request threw", e);
    return turnCache?.servers ?? STUN_ONLY;
  }
  if (!res.ok) {
    console.error("TURN credential request failed", res.status, await res.text());
    return turnCache?.servers ?? STUN_ONLY;
  }
  const body = (await res.json()) as { iceServers?: unknown };
  const list = Array.isArray(body.iceServers) ? body.iceServers : body.iceServers ? [body.iceServers] : [];
  const servers: IceServer[] = [];
  for (const item of list as Array<Partial<IceServer> & { urls?: string | string[] }>) {
    const urls = typeof item.urls === "string" ? [item.urls] : Array.isArray(item.urls) ? item.urls.filter((u): u is string => typeof u === "string") : [];
    if (urls.length === 0) continue;
    const entry: IceServer = { urls };
    if (typeof item.username === "string") entry.username = item.username;
    if (typeof item.credential === "string") entry.credential = item.credential;
    servers.push(entry);
  }
  if (servers.length === 0) return STUN_ONLY;
  turnCache = { servers, expires: Date.now() + TURN_CACHE_MS };
  return servers;
}

/** Matches the app's `fingerprint()`: base64url of the first 12 bytes of SHA-256 over the key text. */
async function fingerprint(ed25519: string): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ed25519)));
  let s = "";
  for (const b of digest.subarray(0, 12)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function parseJson<T>(text: string): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new HttpError(400, "Body must be JSON");
  }
}
