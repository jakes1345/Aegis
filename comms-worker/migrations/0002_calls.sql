-- Call log. One row per call on the owner's number, in either direction,
-- updated by Twilio's status callbacks and the <Dial> result.

CREATE TABLE IF NOT EXISTS calls (
    id        TEXT PRIMARY KEY,           -- Twilio CallSid (CA…)
    seq       INTEGER NOT NULL UNIQUE,    -- sync cursor
    direction TEXT NOT NULL CHECK (direction IN ('in', 'out')),
    peer      TEXT NOT NULL,              -- the other party, E.164
    status    TEXT NOT NULL,              -- ringing | in-progress | completed | missed | busy | failed | no-answer | canceled
    duration  INTEGER NOT NULL DEFAULT 0, -- seconds, once completed
    ts        INTEGER NOT NULL,           -- epoch millis the call started
    updated   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS calls_updated ON calls (updated);
