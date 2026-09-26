import type { Mailbox } from "./mailbox";

export interface Env {
  MAILBOX: DurableObjectNamespace<Mailbox>;
  /** One-time code typed into Aegis when it registers, so the relay is not open to everyone. */
  ENROLL_SECRET: string;
  /** Optional: Cloudflare Realtime TURN key id + API token, for calls. */
  TURN_KEY_ID?: string;
  TURN_KEY_API_TOKEN?: string;
  /** Optional: AegisCoin minted for every new registration (a whole number, e.g. "100"). Unset or "0": none. */
  COIN_INITIAL_BALANCE?: string;
  /** Optional: protects POST /v1/admin/coin/mint. Unset: minting is off. */
  ADMIN_SECRET?: string;
}

/** A JSON response with the headers every API reply carries. */
export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export class HttpError extends Error {
  constructor(public readonly status: number, message: string, public readonly retryAfter?: number) {
    super(message);
  }
}

export function requireSecret(env: Env, name: "ENROLL_SECRET" | "ADMIN_SECRET"): string {
  const value = env[name];
  if (typeof value !== "string" || value.length === 0) {
    throw new HttpError(503, `Relay secret ${name} is not configured`);
  }
  // It is typed into each phone once; short ones could be guessed.
  if (value.length < 16) throw new HttpError(503, `Relay secret ${name} is too short (16 characters at least)`);
  return value;
}

export function hex(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let out = "";
  for (const b of view) out += b.toString(16).padStart(2, "0");
  return out;
}

export async function sha256Hex(data: ArrayBuffer | Uint8Array | string): Promise<string> {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  return hex(await crypto.subtle.digest("SHA-256", bytes as BufferSource));
}

export function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64.replace(/-/g, "+").replace(/_/g, "/"));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Constant-time comparison of two strings of equal length. */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Aegis numbers are nine digits, never starting with 0, e.g. "482 913 605". */
export const NUMBER_RE = /^[1-9][0-9]{8}$/;

export function normaliseNumber(raw: string): string | null {
  const digits = raw.replace(/\D/g, "");
  return NUMBER_RE.test(digits) ? digits : null;
}

export function randomNumber(): string {
  const bytes = new Uint32Array(3);
  crypto.getRandomValues(bytes);
  const first = 1 + ((bytes[0] ?? 0) % 9);
  let rest = "";
  for (let i = 0; i < 8; i++) {
    const word = bytes[1 + (i >> 2)] ?? 0;
    rest += ((word >>> ((i & 3) * 8)) & 0xff) % 10;
  }
  return `${first}${rest}`;
}
