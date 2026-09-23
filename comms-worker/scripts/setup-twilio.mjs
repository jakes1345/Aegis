#!/usr/bin/env node
/**
 * Points the Twilio number at the deployed Worker and creates the voice
 * resources the Worker needs.
 *
 *   TWILIO_ACCOUNT_SID=AC… TWILIO_AUTH_TOKEN=… TWILIO_NUMBER=+1555… \
 *   WORKER_URL=https://aegis-comms.<account>.workers.dev \
 *   FIREBASE_SERVICE_ACCOUNT_JSON="$(cat service-account.json)" \
 *   node scripts/setup-twilio.mjs
 *
 * Messaging: sets the number's SMS webhook to /twilio/sms and its status
 * callback to /twilio/status.
 *
 * Voice: finds or creates the "Aegis comms" TwiML App with voice URL
 * /twilio/voice and status callback /twilio/voice/status, points the number's
 * voice webhook at the same URL, creates an API key for signing access tokens,
 * and creates an FCM push credential from the Firebase service account. The
 * API key secret and push credential are shown once; the script prints the
 * `wrangler secret put` lines to run.
 *
 * Idempotent for the number and the TwiML App. Set SKIP_VOICE=1 to configure
 * messaging only; set TWILIO_API_KEY_SID / TWILIO_PUSH_CREDENTIAL_SID to reuse
 * existing ones instead of creating new ones.
 */

const {
  TWILIO_ACCOUNT_SID,
  TWILIO_AUTH_TOKEN,
  TWILIO_NUMBER,
  WORKER_URL,
  FIREBASE_SERVICE_ACCOUNT_JSON,
  SKIP_VOICE,
  TWILIO_API_KEY_SID,
  TWILIO_PUSH_CREDENTIAL_SID,
} = process.env;

for (const [name, value] of Object.entries({ TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_NUMBER, WORKER_URL })) {
  if (!value) {
    console.error(`${name} is required`);
    process.exit(2);
  }
}

const auth = "Basic " + Buffer.from(`${TWILIO_ACCOUNT_SID}:${TWILIO_AUTH_TOKEN}`).toString("base64");
const workerUrl = WORKER_URL.replace(/\/+$/, "");
const api = `https://api.twilio.com/2010-04-01/Accounts/${TWILIO_ACCOUNT_SID}`;

async function twilio(url, init = {}) {
  const res = await fetch(url, { ...init, headers: { authorization: auth, ...(init.headers ?? {}) } });
  const data = await res.json();
  if (!res.ok) throw new Error(`${url}: ${data.message ?? res.statusText} (code ${data.code ?? res.status})`);
  return data;
}

const form = (fields) => ({
  method: "POST",
  headers: { "content-type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams(fields),
});

// ── The number ──────────────────────────────────────────────────────────────

const list = await twilio(`${api}/IncomingPhoneNumbers.json?PhoneNumber=${encodeURIComponent(TWILIO_NUMBER)}`);
const number = list.incoming_phone_numbers?.[0];
if (!number) {
  console.error(`Number ${TWILIO_NUMBER} is not on account ${TWILIO_ACCOUNT_SID}. Buy it in the Twilio console first.`);
  process.exit(1);
}

const numberFields = {
  SmsUrl: `${workerUrl}/twilio/sms`,
  SmsMethod: "POST",
  StatusCallback: `${workerUrl}/twilio/status`,
  StatusCallbackMethod: "POST",
};
if (!SKIP_VOICE) {
  numberFields.VoiceUrl = `${workerUrl}/twilio/voice`;
  numberFields.VoiceMethod = "POST";
}
const updated = await twilio(`${api}/IncomingPhoneNumbers/${number.sid}.json`, form(numberFields));
console.log(`Configured ${updated.phone_number} (${updated.sid})`);
console.log(`  SMS webhook     : ${updated.sms_url}`);
console.log(`  Status callback : ${updated.status_callback}`);
console.log(`  Voice webhook   : ${updated.voice_url || "(unset)"}`);

if (SKIP_VOICE) process.exit(0);

// ── TwiML App ───────────────────────────────────────────────────────────────

const appName = "Aegis comms";
const apps = await twilio(`${api}/Applications.json?FriendlyName=${encodeURIComponent(appName)}`);
const appFields = {
  FriendlyName: appName,
  VoiceUrl: `${workerUrl}/twilio/voice`,
  VoiceMethod: "POST",
  StatusCallback: `${workerUrl}/twilio/voice/status`,
  StatusCallbackMethod: "POST",
};
const existingApp = apps.applications?.[0];
const app = existingApp
  ? await twilio(`${api}/Applications/${existingApp.sid}.json`, form(appFields))
  : await twilio(`${api}/Applications.json`, form(appFields));
console.log(`TwiML App ${app.sid} → ${app.voice_url}`);

// ── API key for access tokens ───────────────────────────────────────────────

let apiKeySid = TWILIO_API_KEY_SID;
let apiKeySecret = null;
if (!apiKeySid) {
  const key = await twilio(`${api}/Keys.json`, form({ FriendlyName: "Aegis comms voice tokens" }));
  apiKeySid = key.sid;
  apiKeySecret = key.secret;
  console.log(`API key ${apiKeySid} created (secret shown once below)`);
} else {
  console.log(`API key ${apiKeySid} reused (set TWILIO_API_KEY_SECRET yourself)`);
}

// ── FCM push credential ─────────────────────────────────────────────────────

let pushCredentialSid = TWILIO_PUSH_CREDENTIAL_SID;
if (!pushCredentialSid) {
  if (!FIREBASE_SERVICE_ACCOUNT_JSON) {
    console.error(
      "FIREBASE_SERVICE_ACCOUNT_JSON is required to create the push credential " +
        "(or create it in the Twilio console under Voice → Push Credentials, type FCM, " +
        "pasting the service-account JSON, and pass TWILIO_PUSH_CREDENTIAL_SID).",
    );
    process.exit(2);
  }
  JSON.parse(FIREBASE_SERVICE_ACCOUNT_JSON); // fail early on a bad paste
  // Push credentials live on the (otherwise retired) Notify credential resource;
  // for FCM HTTP v1 the Secret is the whole service-account JSON.
  const credential = await twilio(
    "https://notify.twilio.com/v1/Credentials",
    form({ Type: "fcm", FriendlyName: "Aegis comms FCM", Secret: FIREBASE_SERVICE_ACCOUNT_JSON }),
  );
  pushCredentialSid = credential.sid;
  console.log(`Push credential ${pushCredentialSid} created`);
} else {
  console.log(`Push credential ${pushCredentialSid} reused`);
}

console.log("\nNow set the voice secrets on the Worker:");
console.log(`  npx wrangler secret put TWILIO_TWIML_APP_SID        # ${app.sid}`);
console.log(`  npx wrangler secret put TWILIO_API_KEY_SID          # ${apiKeySid}`);
console.log(`  npx wrangler secret put TWILIO_API_KEY_SECRET       # ${apiKeySecret ?? "(the secret of the reused key)"}`);
console.log(`  npx wrangler secret put TWILIO_PUSH_CREDENTIAL_SID  # ${pushCredentialSid}`);
if (apiKeySecret) console.log("\nThe API key secret above is not retrievable again; store it now.");
