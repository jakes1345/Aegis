import { listDevices } from "./db";
import { HttpError, requireSecret, type Env } from "./env";

/**
 * Voice: access tokens for the Twilio Voice SDK, and the TwiML that connects
 * calls in both directions.
 *
 * Outbound: the app connects to Twilio with the token and a `To` parameter; the
 * TwiML App's voice URL (this Worker) answers with <Dial> to that number, using
 * the owner's number as caller ID. Inbound: a call to the number reaches the
 * same URL; the Worker answers with <Dial> to every paired phone's client
 * identity, which Twilio rings through the FCM push credential.
 */

/** Client identity for a device: Twilio allows [A-Za-z0-9_-], hyphens dropped for safety. */
export function identityFor(deviceId: string): string {
  return "dev" + deviceId.replace(/[^A-Za-z0-9]/g, "");
}

function base64url(input: ArrayBuffer | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : new Uint8Array(input);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export interface VoiceToken {
  token: string;
  identity: string;
  expiresAt: number;
}

/** A one-hour Twilio Access Token with a Voice grant for [deviceId]. */
export async function accessToken(env: Env, deviceId: string): Promise<VoiceToken> {
  const accountSid = requireSecret(env, "TWILIO_ACCOUNT_SID");
  const apiKey = requireSecret(env, "TWILIO_API_KEY_SID");
  const apiSecret = requireSecret(env, "TWILIO_API_KEY_SECRET");
  const appSid = requireSecret(env, "TWILIO_TWIML_APP_SID");
  const pushCredentialSid = requireSecret(env, "TWILIO_PUSH_CREDENTIAL_SID");

  const identity = identityFor(deviceId);
  const now = Math.floor(Date.now() / 1000);
  const ttl = 3600;
  const header = base64url(JSON.stringify({ typ: "JWT", alg: "HS256", cty: "twilio-fpa;v=1" }));
  const payload = base64url(
    JSON.stringify({
      jti: `${apiKey}-${now}`,
      iss: apiKey,
      sub: accountSid,
      nbf: now,
      exp: now + ttl,
      grants: {
        identity,
        voice: {
          incoming: { allow: true },
          outgoing: { application_sid: appSid },
          push_credential_sid: pushCredentialSid,
        },
      },
    }),
  );
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(apiSecret), { name: "HMAC", hash: "SHA-256" }, false, [
    "sign",
  ]);
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${header}.${payload}`));
  return { token: `${header}.${payload}.${base64url(signature)}`, identity, expiresAt: (now + ttl) * 1000 };
}

function xml(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

export function twiml(body: string): Response {
  return new Response(`<?xml version="1.0" encoding="UTF-8"?><Response>${body}</Response>`, {
    headers: { "content-type": "text/xml; charset=utf-8" },
  });
}

/**
 * Answers the voice webhook. Twilio hits it for a call the app places (From is
 * `client:<identity>`) and for a call arriving on the number (From is the
 * caller's number).
 */
export async function voiceWebhook(env: Env, form: URLSearchParams, origin: string): Promise<Response> {
  const ownNumber = requireSecret(env, "TWILIO_NUMBER");
  const from = form.get("From") ?? "";
  const statusUrl = `${origin}/twilio/voice/status`;
  const dialResultUrl = `${origin}/twilio/voice/dial-result`;

  if (from.startsWith("client:")) {
    // App-originated. The signature check has already proved Twilio sent this,
    // and only tokens minted here can originate from a client identity.
    const to = form.get("To") ?? "";
    if (!/^\+[1-9]\d{6,14}$/.test(to)) {
      return twiml(`<Say>That number is not valid.</Say><Hangup/>`);
    }
    return twiml(
      `<Dial callerId="${xml(ownNumber)}" answerOnBridge="true" timeout="45" action="${xml(dialResultUrl)}">` +
        `<Number statusCallback="${xml(statusUrl)}" statusCallbackEvent="initiated ringing answered completed">${xml(to)}</Number>` +
        `</Dial>`,
    );
  }

  // Inbound from the phone network: ring every paired phone.
  const devices = await listDevices(env);
  if (devices.length === 0) {
    return twiml(`<Say>The person you are calling is not available.</Say><Hangup/>`);
  }
  const clients = devices.map((d) => `<Client>${xml(identityFor(d.id))}</Client>`).join("");
  return twiml(`<Dial answerOnBridge="true" timeout="30" action="${xml(dialResultUrl)}">${clients}</Dial>`);
}

/** After <Dial> ends: nothing more to do, and an unanswered inbound call is over. */
export function dialResult(): Response {
  return twiml(`<Hangup/>`);
}

/** Maps Twilio's call status plus the <Dial> outcome to the stored status. */
export function callStatusFrom(callStatus: string | null, dialStatus: string | null, direction: "in" | "out"): string {
  if (dialStatus) {
    switch (dialStatus) {
      case "completed":
        return "completed";
      case "no-answer":
        return direction === "in" ? "missed" : "no-answer";
      case "busy":
        return "busy";
      case "canceled":
        return direction === "in" ? "missed" : "canceled";
      case "failed":
        return "failed";
    }
  }
  switch (callStatus) {
    case "queued":
    case "initiated":
    case "ringing":
      return "ringing";
    case "in-progress":
      return "in-progress";
    case "completed":
      return "completed";
    case "busy":
      return "busy";
    case "no-answer":
      return direction === "in" ? "missed" : "no-answer";
    case "canceled":
      return direction === "in" ? "missed" : "canceled";
    case "failed":
      return "failed";
    default:
      return callStatus ?? "unknown";
  }
}

export function isTerminal(status: string): boolean {
  return ["completed", "missed", "busy", "failed", "no-answer", "canceled"].includes(status);
}

export function requireTwilioCallFields(form: URLSearchParams): { sid: string; from: string; to: string } {
  const sid = form.get("CallSid");
  const from = form.get("From");
  const to = form.get("To");
  if (!sid || from === null || to === null) throw new HttpError(400, "CallSid, From and To required");
  return { sid, from, to };
}
