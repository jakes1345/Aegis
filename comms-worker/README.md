# Aegis comms relay

A Cloudflare Worker that gives the Aegis app a real phone number. Twilio owns the
number; this Worker sits between Twilio and the phone: it receives SMS webhooks,
sends outbound messages through the Twilio API, keeps the conversation in a D1
database, and wakes paired phones through Firebase Cloud Messaging.

What is and is not encrypted: traffic between Aegis and this Worker, and between
this Worker and Twilio, is TLS. Messages to and from ordinary phones travel the
carrier network as normal SMS and are readable by the carriers and by Twilio.
The push to the phone carries only a kind and a cursor, never message content.

## One-time setup

1. Buy a number with SMS and voice capability in the Twilio console.
2. Create a Firebase project, add an Android app with package `com.xat.aegis`,
   put the downloaded `google-services.json` in `android/app/`, and create a
   service-account key (Project settings → Service accounts).
3. In this directory:

```sh
npm install
npx wrangler login                      # or set CLOUDFLARE_API_TOKEN / CLOUDFLARE_ACCOUNT_ID
npx wrangler d1 create aegis-comms      # paste the database_id into wrangler.toml
npm run migrate
npx wrangler secret put TWILIO_ACCOUNT_SID
npx wrangler secret put TWILIO_AUTH_TOKEN
npx wrangler secret put TWILIO_NUMBER               # E.164, e.g. +15551234567
npx wrangler secret put FIREBASE_SERVICE_ACCOUNT_JSON  # paste the whole key file
npx wrangler secret put ENROLL_SECRET               # a long random string you will type into Aegis once
npm run deploy                                      # prints the Worker URL
WORKER_URL=https://aegis-comms.<account>.workers.dev \
TWILIO_ACCOUNT_SID=… TWILIO_AUTH_TOKEN=… TWILIO_NUMBER=+1… \
FIREBASE_SERVICE_ACCOUNT_JSON="$(cat service-account.json)" npm run setup:twilio
# …then run the four `wrangler secret put` lines it prints (TwiML App, API key
# SID and secret, push credential) and `npm run deploy` once more.
```

4. In Aegis, open the COMMS tab, enter the Worker URL and the enrollment secret.
   The phone receives a bearer token, registers its push token, and registers
   with Twilio Voice so calls to the number ring it.

Calls: the app connects to Twilio with a one-hour access token minted by
`/api/voice/token`; the TwiML App's voice URL is `/twilio/voice`, which dials
the requested number with the owner's number as caller ID. A call to the
number reaches the same URL and rings every paired phone through the FCM push
credential. Audio between the app and Twilio is SRTP; from Twilio onward it is
ordinary telephony.

## API

Twilio webhooks are verified with `X-Twilio-Signature`. Phone endpoints require
`Authorization: Bearer <token>` from enrollment.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/twilio/sms` | Inbound SMS/MMS |
| POST | `/twilio/status` | Delivery status for outbound messages |
| POST | `/api/enroll` | `{secret, name}` → `{deviceId, token, number}` |
| GET | `/api/status` | Number, this device, paired devices |
| PUT | `/api/device/fcm` | `{token}` registers the FCM token |
| DELETE | `/api/device` | Unpairs this phone |
| GET | `/api/messages?after=N` | Messages with `seq > N`, oldest first |
| GET | `/api/messages/updates?since=T` | Status changes after epoch-millis `T` |
| POST | `/api/messages` | `{to, body}` sends an SMS |
| POST | `/api/messages/:sid/refresh` | Re-reads a message's status from Twilio |
| POST | `/twilio/voice` | TwiML for app-placed and inbound calls |
| POST | `/twilio/voice/dial-result` | After `<Dial>` finishes |
| POST | `/twilio/voice/status` | Call progress → call log |
| GET | `/api/voice/token` | `{token, identity, expiresAt, number}` |
| GET | `/api/calls?since=T` | Call-log rows changed after epoch-millis `T` |

## Development

`npm run typecheck` runs the TypeScript compiler. `npm run dev` runs the Worker
locally with a local D1 (`npm run migrate:local` first); Twilio webhooks need a
public URL, so end-to-end testing is done against the deployed Worker.
