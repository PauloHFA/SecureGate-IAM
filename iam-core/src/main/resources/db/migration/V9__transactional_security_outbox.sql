CREATE TABLE iam_audit.outbox_events (
  event_id UUID PRIMARY KEY REFERENCES iam_audit.security_events(id),
  event_type VARCHAR(128) NOT NULL,
  schema_version SMALLINT NOT NULL CHECK (schema_version = 1),
  occurred_at TIMESTAMPTZ NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
  published_at TIMESTAMPTZ,
  CONSTRAINT outbox_payload_contract CHECK (COALESCE((
    jsonb_typeof(payload) = 'object'
    AND octet_length(payload::text) <= 65536
    AND payload ?& ARRAY['eventId','eventType','schemaVersion','occurredAt','producer','correlationId','actor','context','data']
    AND payload->>'eventId' = event_id::text
    AND payload->>'eventType' = event_type
    AND payload->>'schemaVersion' = schema_version::text
    AND jsonb_typeof(payload->'schemaVersion') = 'number'
    AND jsonb_typeof(payload->'occurredAt') = 'string'
    AND (payload->>'occurredAt')::timestamptz = occurred_at
    AND jsonb_typeof(payload->'producer') = 'string'
    AND jsonb_typeof(payload->'correlationId') = 'string'
    AND jsonb_typeof(payload->'actor') = 'string'
    AND jsonb_typeof(payload->'context') = 'object'
    AND jsonb_typeof(payload->'data') = 'object'
  ), false))
);

CREATE INDEX outbox_pending_idx ON iam_audit.outbox_events(created_at, event_id)
WHERE published_at IS NULL;

CREATE FUNCTION iam_audit.reject_outbox_payload_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.event_id IS DISTINCT FROM OLD.event_id
     OR NEW.event_type IS DISTINCT FROM OLD.event_type
     OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.payload IS DISTINCT FROM OLD.payload
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'Outbox payload is immutable' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_payload_immutable BEFORE UPDATE ON iam_audit.outbox_events
FOR EACH ROW EXECUTE FUNCTION iam_audit.reject_outbox_payload_mutation();

COMMENT ON TABLE iam_audit.outbox_events IS
  'Security event envelopes committed with audit/business state; publication begins in stage 6B';
