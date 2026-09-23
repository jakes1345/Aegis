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
- For each envelope: the recipient's Aegis number, a size and a timestamp.
  Not the sender, not the content. Envelopes are sealed to the recipient's
  sealing key with an anonymous box, and inside that sits an Olm double-ratchet
  message the relay could not read even if it opened the box.
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

## API

Signed requests carry `X-Aegis-Number`, `X-Aegis-Ts` (epoch millis, ±5 min),
`X-Aegis-Nonce` (single use) and `X-Aegis-Sig`, an Ed25519 signature over
`number\nts\nnonce\nMETHOD\npath?query\nsha256hex(body)`.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/v1/register` | Body signed by the new identity's key; allocates an Aegis number |
| GET | `/v1/me` | Own profile and one-time-key count |
| DELETE | `/v1/me` | Wipe the mailbox |
| PUT | `/v1/keys` | Replenish one-time keys; rotate the fallback key |
| PUT | `/v1/listed` | Whether the number can be looked up |
| PUT | `/v1/push` | UnifiedPush endpoint to wake this device, or null |
| GET | `/v1/bundle/:number` | A contact's keys and one session key (unlisted numbers need `?pin=<fingerprint>`) |
| POST | `/v1/send` | `{to, envelope}`: queue a sealed envelope for a contact |
| GET | `/v1/inbox` | Waiting envelopes |
| POST | `/v1/ack` | Delete envelopes the app has stored |
| GET | `/v1/ws` | Live delivery over a hibernatable WebSocket |

Each Aegis number is a Durable Object holding the keys, the queue (up to 2000
envelopes of 64 KiB, kept 30 days) and the live sockets.

## Development

`npm run typecheck` runs the TypeScript compiler; `npx wrangler deploy --dry-run`
bundles the Worker. `npm run dev` runs it locally with a local Durable Object.
