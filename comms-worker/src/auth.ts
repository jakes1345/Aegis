import { base64ToBytes, HttpError, sha256Hex } from "./env";

/**
 * Request authentication.
 *
 * Every authenticated request is signed by the caller's Ed25519 identity key,
 * the same key their contacts verify. Nothing else is ever issued: no passwords,
 * no bearer tokens the relay could leak. The signed string binds the caller's
 * Aegis number, a timestamp (±5 minutes), a nonce (single-use within that
 * window), the method, the path and a hash of the body, so a captured request
 * cannot be replayed or altered.
 */

export const TS_WINDOW_MS = 5 * 60_000;

export interface SignedRequest {
  number: string;
  ts: number;
  nonce: string;
  signature: string;
  /** The exact string the signature must cover. */
  payload: string;
}

export async function parseSigned(request: Request, bodyText: string): Promise<SignedRequest> {
  const number = request.headers.get("x-aegis-number") ?? "";
  const tsRaw = request.headers.get("x-aegis-ts") ?? "";
  const nonce = request.headers.get("x-aegis-nonce") ?? "";
  const signature = request.headers.get("x-aegis-sig") ?? "";
  if (!/^[1-9][0-9]{8}$/.test(number)) throw new HttpError(401, "X-Aegis-Number required");
  const ts = Number(tsRaw);
  if (!Number.isFinite(ts)) throw new HttpError(401, "X-Aegis-Ts required");
  if (Math.abs(Date.now() - ts) > TS_WINDOW_MS) throw new HttpError(401, "Request timestamp outside the allowed window");
  if (!/^[A-Za-z0-9_-]{16,64}$/.test(nonce)) throw new HttpError(401, "X-Aegis-Nonce required");
  if (signature.length === 0) throw new HttpError(401, "X-Aegis-Sig required");
  const url = new URL(request.url);
  const payload = signingPayload(number, ts, nonce, request.method, url.pathname + url.search, await sha256Hex(bodyText));
  return { number, ts, nonce, signature, payload };
}

export function signingPayload(number: string, ts: number, nonce: string, method: string, pathAndQuery: string, bodySha256: string): string {
  return `${number}\n${ts}\n${nonce}\n${method.toUpperCase()}\n${pathAndQuery}\n${bodySha256}`;
}

/** Verifies a base64 Ed25519 signature over `message` with a base64 public key. */
export async function verifyEd25519(publicKeyB64: string, message: string, signatureB64: string): Promise<boolean> {
  try {
    const keyBytes = base64ToBytes(publicKeyB64);
    const sigBytes = base64ToBytes(signatureB64);
    if (keyBytes.length !== 32 || sigBytes.length !== 64) return false;
    const key = await crypto.subtle.importKey("raw", keyBytes, { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "Ed25519" }, key, sigBytes, new TextEncoder().encode(message));
  } catch {
    return false;
  }
}
