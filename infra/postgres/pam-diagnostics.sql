-- Administrator only. This narrow diagnostic target contains no application/user/audit rows.
BEGIN;
CREATE SCHEMA IF NOT EXISTS securegate_diagnostics;
REVOKE ALL ON SCHEMA securegate_diagnostics FROM PUBLIC;
CREATE OR REPLACE VIEW securegate_diagnostics.database_health AS
  SELECT datname AS database_name,numbackends,xact_commit,xact_rollback
  FROM pg_catalog.pg_stat_database WHERE datname=current_database();
REVOKE ALL ON securegate_diagnostics.database_health FROM PUBLIC;
DO $$
DECLARE credential TEXT; target OID;
BEGIN
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='securegate_pam_diagnostics') THEN
    CREATE ROLE securegate_pam_diagnostics NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  SELECT oid INTO target FROM pg_roles WHERE rolname='securegate_pam_diagnostics';
  IF EXISTS(SELECT 1 FROM pg_roles WHERE oid=target AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR EXISTS(SELECT 1 FROM pg_auth_members WHERE member=target)
    OR EXISTS(SELECT 1 FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=target AND deptype='o') THEN
    RAISE EXCEPTION 'Unexpected PAM group privileges';
  END IF;
  credential:=current_setting('securegate.pam_issuer_password',true);
  IF credential IS NULL OR credential !~ '^[0-9a-f]{64}$' THEN RAISE EXCEPTION 'Invalid PAM issuer credential'; END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='securegate_vault_pam') THEN
    CREATE ROLE securegate_vault_pam LOGIN NOINHERIT CREATEROLE NOSUPERUSER NOCREATEDB NOREPLICATION NOBYPASSRLS;
  END IF;
  SELECT oid INTO target FROM pg_roles WHERE rolname='securegate_vault_pam';
  IF EXISTS(SELECT 1 FROM pg_roles WHERE oid=target AND (NOT rolcanlogin OR rolinherit OR NOT rolcreaterole OR rolsuper OR rolcreatedb OR rolreplication OR rolbypassrls))
    OR EXISTS(SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid WHERE m.member=target AND
      (r.rolname NOT IN ('securegate_pam_diagnostics','pg_signal_backend') OR (r.rolname='securegate_pam_diagnostics' AND (m.inherit_option OR m.set_option))))
    OR EXISTS(SELECT 1 FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=target AND deptype='o') THEN
    RAISE EXCEPTION 'Unexpected PAM issuer privileges';
  END IF;
  BEGIN EXECUTE format('ALTER ROLE securegate_vault_pam PASSWORD %L',credential);
  EXCEPTION WHEN OTHERS THEN RAISE EXCEPTION 'PAM issuer unavailable' USING ERRCODE='42501'; END;
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM securegate_pam_diagnostics',current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO securegate_vault_pam,securegate_pam_diagnostics',current_database());
  IF has_database_privilege('securegate_pam_diagnostics',current_database(),'CREATE') THEN RAISE EXCEPTION 'Unexpected PAM database CREATE privilege'; END IF;
END $$;
REVOKE ALL ON SCHEMA iam_identity,iam_access,iam_policy,iam_authorization,iam_session,
  iam_audit,iam_audit_archive,public,securegate_diagnostics FROM securegate_pam_diagnostics;
REVOKE ALL ON ALL TABLES IN SCHEMA iam_identity,iam_access,iam_policy,iam_authorization,
  iam_session,iam_audit,iam_audit_archive,public,securegate_diagnostics FROM securegate_pam_diagnostics;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA iam_identity,iam_access,iam_policy,iam_authorization,
  iam_session,iam_audit,iam_audit_archive,public,securegate_diagnostics FROM securegate_pam_diagnostics;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA iam_identity,iam_access,iam_policy,iam_authorization,
  iam_session,iam_audit,iam_audit_archive,public,securegate_diagnostics FROM securegate_pam_diagnostics;
DO $$ DECLARE item RECORD; name TEXT;
BEGIN
  FOR item IN SELECT n.nspname,c.relname,a.attname FROM pg_attribute a
    JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE a.attacl IS NOT NULL AND a.attnum>0 AND NOT a.attisdropped
      AND n.nspname IN ('iam_identity','iam_access','iam_policy','iam_authorization','iam_session','iam_audit','iam_audit_archive','public','securegate_diagnostics')
  LOOP
    EXECUTE format('REVOKE ALL (%I) ON TABLE %I.%I FROM securegate_pam_diagnostics',item.attname,item.nspname,item.relname);
  END LOOP;
  FOREACH name IN ARRAY ARRAY['iam_identity','iam_access','iam_policy','iam_authorization','iam_session','iam_audit','iam_audit_archive','public','securegate_diagnostics'] LOOP
    IF has_schema_privilege('securegate_pam_diagnostics',name,'CREATE') THEN RAISE EXCEPTION 'Unexpected PAM schema CREATE privilege'; END IF;
  END LOOP;
  IF has_table_privilege('securegate_pam_diagnostics','iam_identity.users','SELECT,INSERT,UPDATE,DELETE')
    OR has_table_privilege('securegate_pam_diagnostics','iam_audit.security_events','SELECT,INSERT,UPDATE,DELETE') THEN RAISE EXCEPTION 'Unexpected inherited PAM data access'; END IF;
END $$;
GRANT USAGE ON SCHEMA securegate_diagnostics TO securegate_pam_diagnostics;
GRANT SELECT ON securegate_diagnostics.database_health TO securegate_pam_diagnostics;
GRANT securegate_pam_diagnostics TO securegate_vault_pam WITH ADMIN TRUE,INHERIT FALSE,SET FALSE;
GRANT pg_signal_backend TO securegate_vault_pam WITH INHERIT TRUE,SET FALSE;
COMMIT;
