import { HttpError, requireSecret, type Env } from "./env";

const API = "https://api.twilio.com/2010-04-01";

function basicAuth(env: Env): string {
  const sid = requireSecret(env, "TWILIO_ACCOUNT_SID");
  const token = requireSecret(env, "TWILIO_AUTH_TOKEN");
  return "Basic " + btoa(`${sid}:${token}`);
}

/**
 * Validates X-Twilio-Signature on a webhook: base64(HMAC-SHA1(authToken,
 * url + concat(sorted(key + value)))), with the URL exactly as Twilio requested it.
 * Anyone can POST to the webhook path; without this, anyone could inject
 * "received" messages into the owner's conversation.
 */
export async function verifyWebhook(env: Env, request: Request, form: URLSearchParams): Promise<void> {
  const signature = request.headers.get("x-twilio-signature");
  if (!signature) throw new HttpError(403, "Missing Twilio signature");
  const authToken = requireSecret(env, "TWILIO_AUTH_TOKEN");

  // Twilio signs the public URL. Behind Cloudflare the Worker sees the same
  // scheme, host and path, but a proxy could present http; force https, which
  // is the only scheme the number is configured with.
  const url = new URL(request.url);
  url.protocol = "https:";
  const keys = [...form.keys()].sort();
  let payload = url.toString();
  for (const k of keys) payload += k + (form.get(k) ?? "");

  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(authToken),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload));
  const expected = btoa(String.fromCharCode(...new Uint8Array(mac)));
  if (expected.length !== signature.length) throw new HttpError(403, "Bad Twilio signature");
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ signature.charCodeAt(i);
  if (diff !== 0) throw new HttpError(403, "Bad Twilio signature");
}

export interface SentMessage {
  sid: string;
  status: string;
  errorCode: number | null;
  errorMessage: string | null;
}

/** Sends an SMS from the owner's number through the Messages API. */
export async function sendSms(env: Env, to: string, body: string, statusCallback: string): Promise<SentMessage> {
  const sid = requireSecret(env, "TWILIO_ACCOUNT_SID");
  const from = requireSecret(env, "TWILIO_NUMBER");
  const params = new URLSearchParams({ From: from, To: to, Body: body, StatusCallback: statusCallback });
  const res = await fetch(`${API}/Accounts/${sid}/Messages.json`, {
    method: "POST",
    headers: { authorization: basicAuth(env), "content-type": "application/x-www-form-urlencoded" },
    body: params,
  });
  const data = (await res.json()) as {
    sid?: string;
    status?: string;
    error_code?: number | null;
    error_message?: string | null;
    message?: string;
    code?: number;
  };
  if (!res.ok || !data.sid) {
    throw new HttpError(502, `Twilio refused the message: ${data.message ?? res.statusText} (code ${data.code ?? res.status})`);
  }
  return {
    sid: data.sid,
    status: data.status ?? "queued",
    errorCode: data.error_code ?? null,
    errorMessage: data.error_message ?? null,
  };
}

/** The account's copy of a message, for reconciling a missed callback. */
export async function fetchMessage(env: Env, messageSid: string): Promise<SentMessage | null> {
  const sid = requireSecret(env, "TWILIO_ACCOUNT_SID");
  const res = await fetch(`${API}/Accounts/${sid}/Messages/${messageSid}.json`, {
    headers: { authorization: basicAuth(env) },
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new HttpError(502, `Twilio lookup failed: ${res.status}`);
  const data = (await res.json()) as { sid: string; status: string; error_code: number | null; error_message: string | null };
  return { sid: data.sid, status: data.status, errorCode: data.error_code, errorMessage: data.error_message };
}

/** Twilio's TwiML for a webhook that needs no reply: an empty response. */
export function emptyTwiml(): Response {
  return new Response('<?xml version="1.0" encoding="UTF-8"?><Response></Response>', {
    headers: { "content-type": "text/xml; charset=utf-8" },
  });
}
