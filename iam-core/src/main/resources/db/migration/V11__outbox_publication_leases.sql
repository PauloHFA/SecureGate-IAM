ALTER TABLE iam_audit.outbox_events
  ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 1000000),
  ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
  ADD COLUMN claim_id UUID,
  ADD COLUMN lease_until TIMESTAMPTZ,
  ADD COLUMN last_error_code VARCHAR(32),
  ADD CONSTRAINT outbox_lease_pair CHECK ((claim_id IS NULL) = (lease_until IS NULL)),
  ADD CONSTRAINT outbox_published_unclaimed CHECK (published_at IS NULL OR claim_id IS NULL),
  ADD CONSTRAINT outbox_error_code CHECK (last_error_code IS NULL OR last_error_code IN
    ('UNROUTABLE','NACK','CONFIRM_TIMEOUT','BROKER_UNAVAILABLE','INVALID_EVENT'));

CREATE INDEX outbox_due_idx ON iam_audit.outbox_events(next_attempt_at, created_at, event_id)
WHERE published_at IS NULL;

COMMENT ON COLUMN iam_audit.outbox_events.claim_id IS
  'Fencing token for a temporary publisher lease; never part of the event payload';
