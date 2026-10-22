ALTER TABLE iam_audit.outbox_events
  ADD COLUMN trace_parent VARCHAR(55),
  ADD CONSTRAINT outbox_trace_parent_contract CHECK (
    trace_parent IS NULL OR (
      trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$'
      AND substring(trace_parent FROM 4 FOR 32) <> repeat('0',32)
      AND substring(trace_parent FROM 37 FOR 16) <> repeat('0',16)
    )
  );

CREATE OR REPLACE FUNCTION iam_audit.reject_outbox_payload_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.event_id IS DISTINCT FROM OLD.event_id
     OR NEW.event_type IS DISTINCT FROM OLD.event_type
     OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
     OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
     OR NEW.trace_parent IS DISTINCT FROM OLD.trace_parent
     OR NEW.payload IS DISTINCT FROM OLD.payload
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'Outbox payload is immutable' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$$;

COMMENT ON COLUMN iam_audit.outbox_events.trace_parent IS
  'Optional immutable W3C version-00 parent captured in the event transaction; no baggage or tracestate';
