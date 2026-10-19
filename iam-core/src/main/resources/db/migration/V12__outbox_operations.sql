ALTER TABLE iam_audit.outbox_events
  ADD COLUMN quarantined_at TIMESTAMPTZ,
  ADD CONSTRAINT outbox_quarantine_unclaimed CHECK
    (quarantined_at IS NULL OR (published_at IS NULL AND claim_id IS NULL));

CREATE INDEX outbox_retention_idx ON iam_audit.outbox_events(published_at,event_id)
WHERE published_at IS NOT NULL;

DROP INDEX iam_audit.outbox_due_idx;
CREATE INDEX outbox_due_idx ON iam_audit.outbox_events(next_attempt_at,created_at,event_id)
WHERE published_at IS NULL AND quarantined_at IS NULL;

CREATE TABLE iam_audit.outbox_operations (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  database_actor TEXT NOT NULL DEFAULT session_user,
  action VARCHAR(16) NOT NULL CHECK (action IN ('QUARANTINE','RELEASE')),
  reason VARCHAR(32) NOT NULL CHECK (reason IN ('INVALID_EVENT','OPERATOR'))
);

CREATE FUNCTION iam_audit.record_outbox_quarantine() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF (NEW.quarantined_at IS NULL) IS DISTINCT FROM (OLD.quarantined_at IS NULL) THEN
    INSERT INTO iam_audit.outbox_operations(event_id,action,reason)
    VALUES (NEW.event_id,
      CASE WHEN NEW.quarantined_at IS NULL THEN 'RELEASE' ELSE 'QUARANTINE' END,
      CASE WHEN NEW.quarantined_at IS NOT NULL AND NEW.last_error_code='INVALID_EVENT'
        THEN 'INVALID_EVENT' ELSE 'OPERATOR' END);
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_quarantine_history AFTER UPDATE ON iam_audit.outbox_events
FOR EACH ROW EXECUTE FUNCTION iam_audit.record_outbox_quarantine();

CREATE FUNCTION iam_audit.reject_outbox_operation_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Outbox operation history is append-only' USING ERRCODE='42501';
END;
$$;

CREATE TRIGGER outbox_operations_immutable BEFORE UPDATE OR DELETE ON iam_audit.outbox_operations
FOR EACH ROW EXECUTE FUNCTION iam_audit.reject_outbox_operation_mutation();

-- Trusted database operators only; this function never edits payloads or marks delivery.
CREATE FUNCTION iam_audit.set_outbox_quarantine(target UUID, quarantine BOOLEAN) RETURNS BOOLEAN
LANGUAGE plpgsql AS $$
DECLARE affected INTEGER;
BEGIN
  IF target IS NULL OR quarantine IS NULL THEN
    RAISE EXCEPTION 'Event and quarantine state are required' USING ERRCODE='22023';
  END IF;
  UPDATE iam_audit.outbox_events
  SET quarantined_at=CASE WHEN quarantine THEN clock_timestamp() ELSE NULL END,
      claim_id=NULL,lease_until=NULL,last_error_code=NULL,next_attempt_at=clock_timestamp()
  WHERE event_id=target AND published_at IS NULL
    AND ((quarantine AND quarantined_at IS NULL) OR (NOT quarantine AND quarantined_at IS NOT NULL));
  GET DIAGNOSTICS affected = ROW_COUNT;
  RETURN affected=1;
END;
$$;

REVOKE ALL ON FUNCTION iam_audit.set_outbox_quarantine(UUID,BOOLEAN) FROM PUBLIC;

COMMENT ON TABLE iam_audit.outbox_operations IS
  'Operational quarantine transitions; no payload; survives published outbox cleanup';
