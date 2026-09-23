-- Aegis comms relay schema.
--
-- messages holds every SMS in either direction on the owner's number. seq is a
-- dense, monotonically increasing cursor so the phone can ask "everything after
-- N" and never miss or duplicate a row, whatever order Twilio's webhooks land in.

CREATE TABLE IF NOT EXISTS messages (
    id        TEXT PRIMARY KEY,           -- Twilio MessageSid (SM…/MM…)
    seq       INTEGER NOT NULL UNIQUE,    -- sync cursor, assigned on insert
    direction TEXT NOT NULL CHECK (direction IN ('in', 'out')),
    peer      TEXT NOT NULL,              -- the other party, E.164
    body      TEXT NOT NULL,
    media     TEXT NOT NULL DEFAULT '[]', -- JSON array of {url, contentType} for MMS
    status    TEXT NOT NULL,              -- received | queued | sent | delivered | undelivered | failed
    error     TEXT,                       -- Twilio error code + message when failed
    ts        INTEGER NOT NULL,           -- epoch millis
    updated   INTEGER NOT NULL            -- epoch millis of the last status change
);

CREATE INDEX IF NOT EXISTS messages_peer_ts ON messages (peer, ts);
CREATE INDEX IF NOT EXISTS messages_updated ON messages (updated);

-- One row per paired phone. The bearer token is stored hashed; the plaintext is
-- handed out exactly once, at enrollment, and lives in the phone's Keystore-bound
-- storage.
CREATE TABLE IF NOT EXISTS devices (
    id         TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,      -- SHA-256 of the bearer token, hex
    name       TEXT NOT NULL,
    fcm_token  TEXT,                      -- Firebase registration token, or NULL until reported
    created_at INTEGER NOT NULL,
    last_seen  INTEGER NOT NULL
);

-- Status callbacks that arrive before the send response has been stored (Twilio
-- can be quicker than the round trip) wait here and are applied on insert.
CREATE TABLE IF NOT EXISTS pending_status (
    id      TEXT PRIMARY KEY,             -- MessageSid
    status  TEXT NOT NULL,
    error   TEXT,
    updated INTEGER NOT NULL
);
