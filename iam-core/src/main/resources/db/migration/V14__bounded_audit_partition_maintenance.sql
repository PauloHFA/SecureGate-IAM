-- Only this bounded entry point will be granted to the runtime maintenance identity.
CREATE FUNCTION iam_audit.maintain_event_partitions(months_ahead INTEGER)
RETURNS INTEGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog SET TimeZone = 'UTC' SET DateStyle = 'ISO, YMD' AS $$
BEGIN
  IF months_ahead IS NULL OR months_ahead < 1 OR months_ahead > 12 THEN
    RAISE EXCEPTION 'Invalid audit maintenance horizon' USING ERRCODE = '22023';
  END IF;
  RETURN iam_audit.ensure_event_partitions(
    (date_trunc('month', clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '1 month')::date,
    months_ahead + 2);
END;
$$;
REVOKE ALL ON FUNCTION iam_audit.maintain_event_partitions(INTEGER) FROM PUBLIC;
-- Roles and credentials are provisioned separately by a PostgreSQL administrator, not Flyway.
COMMENT ON FUNCTION iam_audit.maintain_event_partitions(INTEGER) IS
  'Runtime maintenance: previous/current UTC month and 1..12 future months only';
