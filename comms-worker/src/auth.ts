import { deviceByTokenHash, insertDevice, touchDevice, type DeviceRow } from "./db";
import { hex, HttpError, requireSecret, sha256Hex, timingSafeEqual, type Env } from "./env";

/**
 * Device authentication.
 *
 * Pairing: the owner types ENROLL_SECRET into Aegis once. The Worker answers with
 * a random 256-bit bearer token, stores only its SHA-256, and the phone keeps the
 * token in Keystore-bound storage. Every API call after that carries the token;
 * the secret is never sent again. Rotating ENROLL_SECRET does not log out paired
 * phones; deleting the device row does.
 */

export async function enroll(env: Env, secret: string, name: string, now: number): Promise<{ device: DeviceRow; token: string }> {
  const expected = requireSecret(env, "ENROLL_SECRET");
  if (!timingSafeEqual(await sha256Hex(secret), await sha256Hex(expected))) {
    throw new HttpError(403, "Enrollment code rejected");
  }
  const tokenBytes = new Uint8Array(32);
  crypto.getRandomValues(tokenBytes);
  const token = hex(tokenBytes);
  const device: DeviceRow = {
    id: crypto.randomUUID(),
    token_hash: await sha256Hex(token),
    name: name.slice(0, 64) || "Aegis",
    fcm_token: null,
    created_at: now,
    last_seen: now,
  };
  await insertDevice(env, device);
  return { device, token };
}

/** The device behind a request's bearer token, or a 401. */
export async function authenticate(env: Env, request: Request, now: number): Promise<DeviceRow> {
  const header = request.headers.get("authorization") ?? "";
  const match = /^Bearer\s+([0-9a-f]{64})$/i.exec(header);
  if (!match || !match[1]) throw new HttpError(401, "Device token required");
  const device = await deviceByTokenHash(env, await sha256Hex(match[1].toLowerCase()));
  if (!device) throw new HttpError(401, "Device token not recognised");
  // Fire-and-forget bookkeeping; the caller's ctx.waitUntil is not needed for a
  // single UPDATE that D1 completes promptly.
  await touchDevice(env, device.id, now);
  return device;
}
