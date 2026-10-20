-- Administrator provisioning, after V14. No password or global role is created by Flyway.
BEGIN;
DO $$
DECLARE role_name TEXT;
BEGIN
  IF to_regprocedure('iam_audit.maintain_event_partitions(integer)') IS NULL THEN
    RAISE EXCEPTION 'Apply Flyway V14 before provisioning audit identities';
  END IF;
  FOREACH role_name IN ARRAY ARRAY['securegate_audit_ingest', 'securegate_audit_maintenance'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=role_name) THEN
      EXECUTE format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', role_name);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=role_name AND
       (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
       OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=role_name))
       OR EXISTS (SELECT 1 FROM pg_class WHERE relnamespace='iam_audit'::regnamespace AND relowner=(SELECT oid FROM pg_roles WHERE rolname=role_name))
       OR EXISTS (SELECT 1 FROM pg_namespace WHERE oid='iam_audit'::regnamespace AND nspowner=(SELECT oid FROM pg_roles WHERE rolname=role_name))
       OR EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace='iam_audit'::regnamespace AND proowner=(SELECT oid FROM pg_roles WHERE rolname=role_name)) THEN
      RAISE EXCEPTION 'Unexpected existing audit role privileges';
    END IF;
  END LOOP;
END;
$$;

REVOKE ALL ON SCHEMA iam_audit FROM securegate_audit_ingest, securegate_audit_maintenance;
GRANT USAGE ON SCHEMA iam_audit TO securegate_audit_ingest, securegate_audit_maintenance;
REVOKE ALL ON ALL TABLES IN SCHEMA iam_audit FROM securegate_audit_ingest, securegate_audit_maintenance;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA iam_audit FROM securegate_audit_ingest, securegate_audit_maintenance;
GRANT INSERT, SELECT ON iam_audit.event_log TO securegate_audit_ingest;
GRANT EXECUTE ON FUNCTION iam_audit.maintain_event_partitions(INTEGER) TO securegate_audit_maintenance;

DO $$
DECLARE
  login_name TEXT;
  group_name TEXT;
  credential TEXT;
BEGIN
  FOREACH group_name IN ARRAY ARRAY['securegate_audit_ingest', 'securegate_audit_maintenance'] LOOP
    login_name := group_name || '_login';
    credential := current_setting('securegate.' || group_name || '_password', true);
    IF credential IS NULL OR credential !~ '^[0-9a-f]{64}$' THEN
      RAISE EXCEPTION 'Missing generated audit credential';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=login_name) THEN
      EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', login_name);
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname=login_name AND
        (NOT rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
       OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=login_name)
          AND roleid<>(SELECT oid FROM pg_roles WHERE rolname=group_name))
       OR EXISTS (SELECT 1 FROM pg_class WHERE relnamespace='iam_audit'::regnamespace AND relowner=(SELECT oid FROM pg_roles WHERE rolname=login_name))
       OR EXISTS (SELECT 1 FROM pg_namespace WHERE oid='iam_audit'::regnamespace AND nspowner=(SELECT oid FROM pg_roles WHERE rolname=login_name))
       OR EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace='iam_audit'::regnamespace AND proowner=(SELECT oid FROM pg_roles WHERE rolname=login_name)) THEN
      RAISE EXCEPTION 'Unexpected existing audit login privileges';
    END IF;
    BEGIN
      EXECUTE format('ALTER ROLE %I INHERIT PASSWORD %L', login_name, credential);
    EXCEPTION WHEN OTHERS THEN
      RAISE EXCEPTION 'Audit credential configuration failed' USING ERRCODE = '42501';
    END;
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA iam_audit FROM %I', login_name);
    EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA iam_audit FROM %I', login_name);
    EXECUTE format('REVOKE ALL ON SCHEMA iam_audit FROM %I', login_name);
    EXECUTE format('GRANT %I TO %I', group_name, login_name);
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), login_name);
    EXECUTE format('ALTER ROLE %I SET statement_timeout = %L', login_name, '5s');
    EXECUTE format('ALTER ROLE %I SET lock_timeout = %L', login_name, '1s');
    EXECUTE format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', login_name, '10s');
    IF has_schema_privilege(login_name, 'iam_audit', 'CREATE')
        OR has_table_privilege(login_name, 'iam_audit.security_events', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
        OR has_table_privilege(login_name, 'iam_audit.outbox_events', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE') THEN
      RAISE EXCEPTION 'Unexpected inherited audit privileges';
    END IF;
  END LOOP;
END;
$$;
COMMIT;
