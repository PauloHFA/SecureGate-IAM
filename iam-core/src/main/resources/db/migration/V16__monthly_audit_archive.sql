-- In-database archive only. No automatic detach, data purge or receipt deletion during migration.
CREATE SCHEMA iam_audit_archive;
REVOKE ALL ON SCHEMA iam_audit_archive FROM PUBLIC;

CREATE TABLE iam_audit.archived_months (
  month DATE PRIMARY KEY CHECK (month = date_trunc('month', month)::date),
  table_name TEXT NOT NULL UNIQUE CHECK (table_name ~ '^event_log_[0-9]{6}$'),
  row_count BIGINT NOT NULL CHECK (row_count >= 0),
  hot_months INTEGER NOT NULL CHECK (hot_months BETWEEN 2 AND 120),
  archived_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER archived_months_no_mutation BEFORE UPDATE OR DELETE ON iam_audit.archived_months
FOR EACH ROW EXECUTE FUNCTION iam_audit.reject_event_mutation();
CREATE TRIGGER archived_months_no_truncate BEFORE TRUNCATE ON iam_audit.archived_months
FOR EACH STATEMENT EXECUTE FUNCTION iam_audit.reject_event_mutation();

-- Updated owner-only historical provisioning: never silently reopen a retired month.
CREATE OR REPLACE FUNCTION iam_audit.ensure_event_partitions(first_month DATE, month_count INTEGER)
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
  PERFORM pg_advisory_xact_lock(81263, 8);
  FOR i IN 0..month_count-1 LOOP
    start_at := (date_trunc('month', first_month::timestamp) AT TIME ZONE 'UTC') + (i * INTERVAL '1 month');
    end_at := start_at + INTERVAL '1 month';
    IF EXISTS (SELECT 1 FROM iam_audit.archived_months WHERE month=(start_at AT TIME ZONE 'UTC')::date) THEN
      RAISE EXCEPTION 'Archived audit month cannot be recreated' USING ERRCODE = '55000';
    END IF;
    child_name := 'event_log_' || to_char(start_at, 'YYYYMM');
    child_oid := to_regclass(format('iam_audit.%I', child_name));
    IF child_oid IS NULL THEN
      EXECUTE format('CREATE TABLE iam_audit.%I PARTITION OF iam_audit.event_log FOR VALUES FROM (%L) TO (%L)', child_name, start_at, end_at);
      child_oid := to_regclass(format('iam_audit.%I', child_name));
      created := created + 1;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_inherits WHERE inhrelid=child_oid AND inhparent='iam_audit.event_log'::regclass)
        OR (SELECT pg_get_expr(relpartbound, oid) FROM pg_class WHERE oid=child_oid)
           <> format('FOR VALUES FROM (%L) TO (%L)', start_at, end_at) THEN
      RAISE EXCEPTION 'Unexpected audit partition definition' USING ERRCODE = '55000';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid=child_oid AND tgname='event_log_no_truncate') THEN
      EXECUTE format('CREATE TRIGGER event_log_no_truncate BEFORE TRUNCATE ON iam_audit.%I FOR EACH STATEMENT EXECUTE FUNCTION iam_audit.reject_event_mutation()', child_name);
    END IF;
  END LOOP;
  RETURN created;
END;
$$;
REVOKE ALL ON FUNCTION iam_audit.ensure_event_partitions(DATE, INTEGER) FROM PUBLIC;

CREATE FUNCTION iam_audit.archive_event_month(first_month DATE, hot_months INTEGER DEFAULT 6)
RETURNS BIGINT LANGUAGE plpgsql
SET search_path = pg_catalog SET TimeZone = 'UTC' SET DateStyle = 'ISO, YMD' AS $$
DECLARE
  start_at TIMESTAMPTZ;
  end_at TIMESTAMPTZ;
  cutoff DATE;
  child_name TEXT;
  child_oid REGCLASS;
  recorded iam_audit.archived_months%ROWTYPE;
  total BIGINT;
BEGIN
  IF first_month IS NULL OR first_month < DATE '1970-01-01' OR first_month >= DATE '9998-01-01'
      OR first_month <> date_trunc('month', first_month)::date
      OR hot_months IS NULL OR hot_months < 2 OR hot_months > 120 THEN
    RAISE EXCEPTION 'Invalid audit archive bounds' USING ERRCODE = '22023';
  END IF;
  cutoff := (date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC') - ((hot_months-1)*INTERVAL '1 month'))::date;
  IF first_month >= cutoff THEN
    RAISE EXCEPTION 'Audit month is inside hot retention window' USING ERRCODE = '22023';
  END IF;
  PERFORM pg_advisory_xact_lock(81263, 8);
  child_name := 'event_log_' || to_char(first_month, 'YYYYMM');
  SELECT * INTO recorded FROM iam_audit.archived_months WHERE month=first_month;
  IF FOUND THEN
    IF recorded.table_name <> child_name OR to_regclass(format('iam_audit_archive.%I', child_name)) IS NULL
        OR to_regclass(format('iam_audit.%I', child_name)) IS NOT NULL THEN
      RAISE EXCEPTION 'Unexpected archived audit relation' USING ERRCODE = '55000';
    END IF;
    RETURN recorded.row_count;
  END IF;
  -- Serializes routing, counting and detach with ingestion/query and maintenance.
  LOCK TABLE iam_audit.event_log IN ACCESS EXCLUSIVE MODE;
  child_oid := to_regclass(format('iam_audit.%I', child_name));
  start_at := first_month::timestamp AT TIME ZONE 'UTC';
  end_at := start_at + INTERVAL '1 month';
  IF child_oid IS NULL
      OR NOT EXISTS (SELECT 1 FROM pg_inherits WHERE inhrelid=child_oid AND inhparent='iam_audit.event_log'::regclass)
      OR (SELECT pg_get_expr(relpartbound, oid) FROM pg_class WHERE oid=child_oid)
          <> format('FOR VALUES FROM (%L) TO (%L)', start_at, end_at)
      OR to_regclass(format('iam_audit_archive.%I', child_name)) IS NOT NULL THEN
    RAISE EXCEPTION 'Unexpected audit archive candidate' USING ERRCODE = '55000';
  END IF;
  EXECUTE format('SELECT count(*) FROM iam_audit.%I', child_name) INTO total;
  EXECUTE format('ALTER TABLE iam_audit.event_log DETACH PARTITION iam_audit.%I', child_name);
  EXECUTE format('ALTER TABLE iam_audit.%I SET SCHEMA iam_audit_archive', child_name);
  -- Independent guards survive detach even if inherited row triggers are removed.
  EXECUTE format('CREATE TRIGGER archive_no_insert BEFORE INSERT ON iam_audit_archive.%I FOR EACH ROW EXECUTE FUNCTION iam_audit.reject_event_mutation()', child_name);
  EXECUTE format('CREATE TRIGGER archive_no_mutation BEFORE UPDATE OR DELETE ON iam_audit_archive.%I FOR EACH ROW EXECUTE FUNCTION iam_audit.reject_event_mutation()', child_name);
  EXECUTE format('CREATE TRIGGER archive_no_truncate BEFORE TRUNCATE ON iam_audit_archive.%I FOR EACH STATEMENT EXECUTE FUNCTION iam_audit.reject_event_mutation()', child_name);
  EXECUTE format('REVOKE ALL ON iam_audit_archive.%I FROM PUBLIC', child_name);
  INSERT INTO iam_audit.archived_months(month,table_name,row_count,hot_months)
    VALUES (first_month,child_name,total,hot_months);
  RETURN total;
END;
$$;
REVOKE ALL ON FUNCTION iam_audit.archive_event_month(DATE, INTEGER) FROM PUBLIC;

-- Read-only capability for ingestion, without exposing receipts/archive tables to its login.
CREATE FUNCTION iam_audit.is_archived_event_replay(envelope JSONB)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog SET TimeZone = 'UTC' SET DateStyle = 'ISO, YMD' AS $$
DECLARE
  event_at TIMESTAMPTZ := (envelope->>'occurredAt')::timestamptz;
  previous iam_audit.event_receipts%ROWTYPE;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM iam_audit.archived_months WHERE month=date_trunc('month',event_at AT TIME ZONE 'UTC')::date) THEN
    RETURN false;
  END IF;
  SELECT * INTO previous FROM iam_audit.event_receipts WHERE id=(envelope->>'eventId')::uuid;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'New audit event targets archived month' USING ERRCODE = 'SG001';
  END IF;
  IF previous.occurred_at IS DISTINCT FROM event_at
      OR previous.fingerprint IS DISTINCT FROM sha256(convert_to(envelope::text,'UTF8')) THEN
    RAISE EXCEPTION 'Conflicting audit event identity' USING ERRCODE = '23505';
  END IF;
  RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION iam_audit.is_archived_event_replay(JSONB) FROM PUBLIC;
COMMENT ON TABLE iam_audit.archived_months IS 'Append-only archive registry; receipts and archived data remain in PostgreSQL';
