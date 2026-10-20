-- Separate bus ingestion from the producer audit referenced by the transactional Outbox.
CREATE TABLE iam_audit.event_receipts (
  id UUID PRIMARY KEY,
  occurred_at TIMESTAMPTZ NOT NULL,
  fingerprint BYTEA NOT NULL CHECK (octet_length(fingerprint) = 32),
  received_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
  UNIQUE (id, occurred_at)
);

CREATE TABLE iam_audit.event_log (
  id UUID NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  schema_version SMALLINT NOT NULL CHECK (schema_version = 1),
  producer VARCHAR(64) NOT NULL,
  correlation_id VARCHAR(64) NOT NULL,
  actor VARCHAR(200) NOT NULL,
  resource VARCHAR(100) NOT NULL,
  payload JSONB NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
  PRIMARY KEY (id, occurred_at),
  FOREIGN KEY (id, occurred_at) REFERENCES iam_audit.event_receipts(id, occurred_at),
  CONSTRAINT event_log_contract CHECK (COALESCE((
    jsonb_typeof(payload) = 'object'
    AND octet_length(payload::text) <= 65536
    AND payload ?& ARRAY['eventId','eventType','schemaVersion','occurredAt','producer','correlationId','actor','context','data']
    AND payload->>'eventId' = id::text
    AND payload->>'eventType' = event_type
    AND payload->>'schemaVersion' = schema_version::text
    AND jsonb_typeof(payload->'schemaVersion') = 'number'
    AND jsonb_typeof(payload->'occurredAt') = 'string'
    AND (payload->>'occurredAt')::timestamptz = occurred_at
    AND payload->>'producer' = producer
    AND payload->>'correlationId' = correlation_id
    AND payload->>'actor' = actor
    AND jsonb_typeof(payload->'context') = 'object'
    AND jsonb_typeof(payload->'data') = 'object'
    AND payload->'context'->>'resource' = resource
  ), false))
) PARTITION BY RANGE (occurred_at);

CREATE INDEX event_log_cursor_idx ON iam_audit.event_log(occurred_at DESC, id DESC);
CREATE INDEX event_log_resource_cursor_idx ON iam_audit.event_log(resource, occurred_at DESC, id DESC);
CREATE INDEX event_log_type_cursor_idx ON iam_audit.event_log(event_type, occurred_at DESC, id DESC);

CREATE FUNCTION iam_audit.register_ingested_event() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $$
DECLARE
  previous iam_audit.event_receipts%ROWTYPE;
  digest BYTEA := sha256(convert_to(NEW.payload::text, 'UTF8'));
BEGIN
  INSERT INTO iam_audit.event_receipts(id, occurred_at, fingerprint)
    VALUES (NEW.id, NEW.occurred_at, digest) ON CONFLICT DO NOTHING;
  IF FOUND THEN RETURN NEW; END IF;
  SELECT * INTO STRICT previous FROM iam_audit.event_receipts WHERE id = NEW.id;
  IF previous.occurred_at IS DISTINCT FROM NEW.occurred_at
      OR previous.fingerprint IS DISTINCT FROM digest THEN
    RAISE EXCEPTION 'Conflicting audit event identity' USING ERRCODE = '23505';
  END IF;
  RETURN NULL; -- Exact canonical replay: no second row in any attached month.
END;
$$;
REVOKE ALL ON FUNCTION iam_audit.register_ingested_event() FROM PUBLIC;

CREATE TRIGGER event_log_dedup BEFORE INSERT ON iam_audit.event_log
FOR EACH ROW EXECUTE FUNCTION iam_audit.register_ingested_event();
CREATE TRIGGER event_log_append_only BEFORE UPDATE OR DELETE ON iam_audit.event_log
FOR EACH ROW EXECUTE FUNCTION iam_audit.reject_event_mutation();
CREATE TRIGGER event_log_no_truncate BEFORE TRUNCATE ON iam_audit.event_log
FOR EACH STATEMENT EXECUTE FUNCTION iam_audit.reject_event_mutation();
CREATE TRIGGER event_receipts_append_only BEFORE UPDATE OR DELETE ON iam_audit.event_receipts
FOR EACH ROW EXECUTE FUNCTION iam_audit.reject_event_mutation();
CREATE TRIGGER event_receipts_no_truncate BEFORE TRUNCATE ON iam_audit.event_receipts
FOR EACH STATEMENT EXECUTE FUNCTION iam_audit.reject_event_mutation();

CREATE FUNCTION iam_audit.ensure_event_partitions(first_month DATE, month_count INTEGER)
RETURNS INTEGER LANGUAGE plpgsql SET search_path = pg_catalog SET TimeZone = 'UTC' SET DateStyle = 'ISO, YMD' AS $$
DECLARE
  start_at TIMESTAMPTZ;
  end_at TIMESTAMPTZ;
  child_name TEXT;
  child_oid REGCLASS;
  created INTEGER := 0;
BEGIN
  IF first_month IS NULL OR month_count IS NULL OR month_count < 1 OR month_count > 24
      OR first_month < DATE '1970-01-01' OR first_month >= DATE '9998-01-01' THEN
    RAISE EXCEPTION 'Invalid audit partition bounds' USING ERRCODE = '22023';
  END IF;
  -- Serialize DDL from independent maintenance processes, only until transaction completion.
  PERFORM pg_advisory_xact_lock(81263, 8);
  FOR i IN 0..month_count-1 LOOP
    start_at := (date_trunc('month', first_month::timestamp) AT TIME ZONE 'UTC') + (i * INTERVAL '1 month');
    end_at := start_at + INTERVAL '1 month';
    child_name := 'event_log_' || to_char(start_at, 'YYYYMM');
    child_oid := to_regclass(format('iam_audit.%I', child_name));
    IF child_oid IS NULL THEN
      EXECUTE format('CREATE TABLE iam_audit.%I PARTITION OF iam_audit.event_log FOR VALUES FROM (%L) TO (%L)', child_name, start_at, end_at);
      child_oid := to_regclass(format('iam_audit.%I', child_name));
      created := created + 1;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_inherits WHERE inhrelid = child_oid AND inhparent = 'iam_audit.event_log'::regclass)
        OR (SELECT pg_get_expr(relpartbound, oid) FROM pg_class WHERE oid = child_oid)
           <> format('FOR VALUES FROM (%L) TO (%L)', start_at, end_at) THEN
      RAISE EXCEPTION 'Unexpected audit partition definition' USING ERRCODE = '55000';
    END IF;
    -- Row triggers are cloned on partitions; statement TRUNCATE triggers are not.
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = child_oid AND tgname = 'event_log_no_truncate') THEN
      EXECUTE format('CREATE TRIGGER event_log_no_truncate BEFORE TRUNCATE ON iam_audit.%I FOR EACH STATEMENT EXECUTE FUNCTION iam_audit.reject_event_mutation()', child_name);
    END IF;
  END LOOP;
  RETURN created;
END;
$$;
REVOKE ALL ON FUNCTION iam_audit.ensure_event_partitions(DATE, INTEGER) FROM PUBLIC;

-- Previous month and current month plus three future months; no catch-all DEFAULT partition.
SELECT iam_audit.ensure_event_partitions(
  (date_trunc('month', current_timestamp AT TIME ZONE 'UTC') - INTERVAL '1 month')::date, 5);

COMMENT ON TABLE iam_audit.event_log IS 'Partitioned bus ingestion; producer security_events/Outbox remain unchanged';
COMMENT ON TABLE iam_audit.event_receipts IS 'Permanent global eventId deduplication; retain receipts when archiving partitions';
