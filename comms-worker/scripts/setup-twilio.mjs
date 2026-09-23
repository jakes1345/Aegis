#!/usr/bin/env node
/**
 * Points the Twilio number at the deployed Worker.
 *
 *   TWILIO_ACCOUNT_SID=AC… TWILIO_AUTH_TOKEN=… TWILIO_NUMBER=+1555… \
 *   WORKER_URL=https://aegis-comms.<account>.workers.dev node scripts/setup-twilio.mjs
 *
 * Sets the number's SMS webhook to /twilio/sms and its status callback to
 * /twilio/status, both POST. Idempotent; re-run after changing the Worker URL.
 */

const { TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_NUMBER, WORKER_URL } = process.env;

for (const [name, value] of Object.entries({ TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_NUMBER, WORKER_URL })) {
  if (!value) {
    console.error(`${name} is required`);
    process.exit(2);
  }
}

const base = `https://api.twilio.com/2010-04-01/Accounts/${TWILIO_ACCOUNT_SID}`;
const auth = "Basic " + Buffer.from(`${TWILIO_ACCOUNT_SID}:${TWILIO_AUTH_TOKEN}`).toString("base64");
const workerUrl = WORKER_URL.replace(/\/+$/, "");

async function twilio(path, init = {}) {
  const res = await fetch(`${base}${path}`, { ...init, headers: { authorization: auth, ...(init.headers ?? {}) } });
  const data = await res.json();
  if (!res.ok) throw new Error(`${path}: ${data.message ?? res.statusText} (code ${data.code ?? res.status})`);
  return data;
}

const list = await twilio(`/IncomingPhoneNumbers.json?PhoneNumber=${encodeURIComponent(TWILIO_NUMBER)}`);
const number = list.incoming_phone_numbers?.[0];
if (!number) {
  console.error(`Number ${TWILIO_NUMBER} is not on account ${TWILIO_ACCOUNT_SID}. Buy it in the Twilio console first.`);
  process.exit(1);
}

const params = new URLSearchParams({
  SmsUrl: `${workerUrl}/twilio/sms`,
  SmsMethod: "POST",
  StatusCallback: `${workerUrl}/twilio/status`,
  StatusCallbackMethod: "POST",
});
const updated = await twilio(`/IncomingPhoneNumbers/${number.sid}.json`, {
  method: "POST",
  headers: { "content-type": "application/x-www-form-urlencoded" },
  body: params,
});

console.log(`Configured ${updated.phone_number} (${updated.sid})`);
console.log(`  SMS webhook     : ${updated.sms_url}`);
console.log(`  Status callback : ${updated.status_callback}`);
console.log(`  Voice webhook   : ${updated.voice_url || "(unset — the calls PR configures this)"}`);
