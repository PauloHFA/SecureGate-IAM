-- The runtime can change delivery metadata without INSERT on operational history.
-- Qualify the target and pin search_path before elevating this trigger function.
CREATE OR REPLACE FUNCTION iam_audit.record_outbox_quarantine() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
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
REVOKE ALL ON FUNCTION iam_audit.record_outbox_quarantine() FROM PUBLIC;
