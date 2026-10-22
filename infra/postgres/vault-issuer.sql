-- Administrator only. Password supplied via a session setting, never in this file.
BEGIN;
DO $$
DECLARE credential TEXT; issuer OID;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='securegate_iam_runtime' AND NOT rolcanlogin) THEN
    RAISE EXCEPTION 'Provision the runtime permission group first';
  END IF;
  credential := current_setting('securegate.vault_issuer_password', true);
  IF credential IS NULL OR credential !~ '^[0-9a-f]{64}$' THEN RAISE EXCEPTION 'Invalid issuer bootstrap credential'; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='securegate_vault_database') THEN
    CREATE ROLE securegate_vault_database LOGIN NOINHERIT CREATEROLE NOSUPERUSER NOCREATEDB NOREPLICATION NOBYPASSRLS;
  END IF;
  SELECT oid INTO issuer FROM pg_roles WHERE rolname='securegate_vault_database';
  IF EXISTS (SELECT 1 FROM pg_roles WHERE oid=issuer AND (NOT rolcanlogin OR rolinherit OR NOT rolcreaterole OR rolsuper OR rolcreatedb OR rolreplication OR rolbypassrls))
    OR EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid WHERE m.member=issuer AND
      (r.rolname NOT IN ('securegate_iam_runtime','pg_signal_backend') OR (r.rolname='securegate_iam_runtime' AND (m.inherit_option OR m.set_option))))
    OR EXISTS (SELECT 1 FROM pg_shdepend WHERE refclassid='pg_authid'::regclass AND refobjid=issuer AND deptype='o') THEN
    RAISE EXCEPTION 'Unexpected Vault issuer privileges';
  END IF;
  BEGIN
    EXECUTE format('ALTER ROLE securegate_vault_database PASSWORD %L',credential);
  EXCEPTION WHEN OTHERS THEN RAISE EXCEPTION 'Vault issuer credential unavailable' USING ERRCODE='42501'; END;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO securegate_vault_database',current_database());
END;
$$;
GRANT securegate_iam_runtime TO securegate_vault_database WITH ADMIN TRUE, INHERIT FALSE, SET FALSE;
GRANT pg_signal_backend TO securegate_vault_database WITH INHERIT TRUE, SET FALSE;
COMMIT;
