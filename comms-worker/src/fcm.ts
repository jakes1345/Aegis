import { requireSecret, type Env } from "./env";

/**
 * Firebase Cloud Messaging, HTTP v1. Authenticated with a service account: a
 * self-signed RS256 JWT is exchanged for a short-lived OAuth access token, which
 * is cached in the isolate until shortly before it expires.
 *
 * Only data messages with no notification payload are sent, and the data is just
 * a kind and a cursor. Google sees that Aegis was woken, never who wrote or what.
 */

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

let cachedToken: { value: string; expiresAt: number } | null = null;

function parseAccount(env: Env): ServiceAccount {
  const raw = requireSecret(env, "FIREBASE_SERVICE_ACCOUNT_JSON");
  const parsed = JSON.parse(raw) as Partial<ServiceAccount>;
  if (!parsed.project_id || !parsed.client_email || !parsed.private_key) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is missing project_id, client_email or private_key");
  }
  return parsed as ServiceAccount;
}

function base64url(input: ArrayBuffer | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : new Uint8Array(input);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToPkcs8(pem: string): ArrayBuffer {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const bin = atob(body);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

async function accessToken(account: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.expiresAt - 60 > now) return cachedToken.value;

  const tokenUri = account.token_uri ?? "https://oauth2.googleapis.com/token";
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(
    JSON.stringify({
      iss: account.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: tokenUri,
      iat: now,
      exp: now + 3600,
    }),
  );
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToPkcs8(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`));
  const assertion = `${header}.${claims}.${base64url(signature)}`;

  const res = await fetch(tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  if (!res.ok) throw new Error(`Google token exchange failed: ${res.status} ${await res.text()}`);
  const data = (await res.json()) as { access_token: string; expires_in: number };
  cachedToken = { value: data.access_token, expiresAt: now + data.expires_in };
  return data.access_token;
}

export type PushResult = "sent" | "unregistered" | "failed";

/**
 * Sends one high-priority data message. "unregistered" means the token is dead
 * (app uninstalled or token rotated) and should be dropped.
 */
export async function sendData(env: Env, fcmToken: string, data: Record<string, string>): Promise<PushResult> {
  const account = parseAccount(env);
  const token = await accessToken(account);
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({
      message: {
        token: fcmToken,
        data,
        android: { priority: "HIGH", ttl: "3600s" },
      },
    }),
  });
  if (res.ok) return "sent";
  const text = await res.text();
  if (res.status === 404 || /UNREGISTERED|NOT_FOUND/.test(text)) return "unregistered";
  console.error("FCM send failed", res.status, text);
  return "failed";
}
