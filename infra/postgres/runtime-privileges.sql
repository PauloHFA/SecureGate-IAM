-- Run as administrator after migrations, never as an application login.
-- NOLOGIN permission group for future Vault-issued logins. No credential is created.
BEGIN;
DO $$
DECLARE target OID;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version='17' AND success) THEN
    RAISE EXCEPTION 'Apply Flyway V17 before provisioning the runtime role';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='securegate_iam_runtime') THEN
    CREATE ROLE securegate_iam_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
      NOREPLICATION NOBYPASSRLS;
  END IF;
  SELECT oid INTO target FROM pg_roles WHERE rolname='securegate_iam_runtime';
  IF EXISTS (SELECT 1 FROM pg_roles WHERE oid=target AND
      (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
      OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=target)
      OR EXISTS (SELECT 1 FROM pg_shdepend WHERE refclassid='pg_authid'::regclass
        AND refobjid=target AND deptype='o') THEN
    RAISE EXCEPTION 'Unexpected runtime role attributes, memberships or ownership';
  END IF;
END;
$$;

-- Explicit lists: new objects are not automatically granted to runtime logins.
REVOKE ALL ON SCHEMA iam_identity, iam_access, iam_policy, iam_authorization,
  iam_session, iam_audit, iam_audit_archive, public FROM securegate_iam_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA iam_identity, iam_access, iam_policy,
  iam_authorization, iam_session, iam_audit, iam_audit_archive, public FROM securegate_iam_runtime;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA iam_identity, iam_access, iam_policy,
  iam_authorization, iam_session, iam_audit, iam_audit_archive, public FROM securegate_iam_runtime;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA iam_identity, iam_access, iam_policy,
  iam_authorization, iam_session, iam_audit, iam_audit_archive, public FROM securegate_iam_runtime;
-- REVOKE table privileges does not remove pre-existing column ACLs.
DO $$
DECLARE column_acl RECORD;
BEGIN
  FOR column_acl IN
    SELECT n.nspname, c.relname, a.attname FROM pg_attribute a
    JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE a.attacl IS NOT NULL AND a.attnum>0 AND NOT a.attisdropped
      AND n.nspname IN ('iam_identity','iam_access','iam_policy','iam_authorization',
        'iam_session','iam_audit','iam_audit_archive','public')
  LOOP
    EXECUTE format('REVOKE ALL (%I) ON TABLE %I.%I FROM securegate_iam_runtime',
      column_acl.attname,column_acl.nspname,column_acl.relname);
  END LOOP;
END;
$$;

GRANT USAGE ON SCHEMA iam_identity, iam_access, iam_policy, iam_authorization,
  iam_session, iam_audit TO securegate_iam_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON
  iam_identity.users, iam_identity.credentials, iam_identity.login_failures,
  iam_identity.mfa_factors, iam_identity.mfa_recovery_codes,
  iam_access.roles, iam_access.groups, iam_access.permissions, iam_access.role_permissions,
  iam_access.user_roles, iam_access.group_members, iam_access.group_roles,
  iam_policy.policies, iam_policy.browser_devices, iam_policy.browser_contexts,
  iam_policy.resource_profiles, iam_policy.trusted_networks,
  iam_authorization.oauth2_registered_client, iam_authorization.oauth2_authorization,
  iam_authorization.oauth2_authorization_consent, iam_authorization.refresh_families,
  iam_authorization.refresh_history, iam_session.mfa_session_state, iam_session.mfa_proofs
  TO securegate_iam_runtime;
GRANT SELECT, INSERT ON iam_audit.security_events TO securegate_iam_runtime;
GRANT SELECT, INSERT, DELETE ON iam_audit.outbox_events TO securegate_iam_runtime;
GRANT UPDATE (published_at, attempts, next_attempt_at, claim_id, lease_until,
  last_error_code, quarantined_at) ON iam_audit.outbox_events TO securegate_iam_runtime;
GRANT SELECT ON iam_audit.outbox_operations TO securegate_iam_runtime;
-- V18 is optional when reprovisioning a historical V17 database.
DO $$ BEGIN
  IF to_regclass('iam_authorization.privileged_requests') IS NOT NULL THEN
    GRANT SELECT, INSERT ON iam_authorization.privileged_requests TO securegate_iam_runtime;
    GRANT UPDATE (revoked_at) ON iam_authorization.privileged_requests TO securegate_iam_runtime;
    IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='iam_authorization' AND table_name='privileged_requests' AND column_name='observation_attempts') THEN
      GRANT UPDATE (observation_attempts,next_observation_at,last_observation_failure_at,last_observation_failure_code) ON iam_authorization.privileged_requests TO securegate_iam_runtime;
    END IF;
    IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='iam_authorization' AND table_name='privileged_requests' AND column_name='expiration_recorded_at') THEN
      GRANT UPDATE (expiration_recorded_at) ON iam_authorization.privileged_requests TO securegate_iam_runtime;
    END IF;
  END IF;
END $$;

DO $$ BEGIN
  IF to_regclass('iam_authorization.privileged_issuances') IS NOT NULL THEN
    GRANT SELECT, INSERT ON iam_authorization.privileged_issuances TO securegate_iam_runtime;
    GRANT UPDATE (state,lease_id,expires_at) ON iam_authorization.privileged_issuances TO securegate_iam_runtime;
    IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='iam_authorization' AND table_name='privileged_issuances' AND column_name='cleanup_attempts') THEN
      GRANT UPDATE (cleanup_attempts,next_cleanup_at,last_cleanup_failure_at,last_cleanup_failure_code) ON iam_authorization.privileged_issuances TO securegate_iam_runtime;
    END IF;
    IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='iam_authorization' AND table_name='privileged_issuances' AND column_name='expiration_recorded_at') THEN
      GRANT UPDATE (expiration_recorded_at) ON iam_authorization.privileged_issuances TO securegate_iam_runtime;
    END IF;
  END IF;
END $$;

DO $$
DECLARE schema_name TEXT;
BEGIN
  EXECUTE format('REVOKE ALL ON DATABASE %I FROM securegate_iam_runtime', current_database());
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO securegate_iam_runtime', current_database());
  FOREACH schema_name IN ARRAY ARRAY['iam_identity','iam_access','iam_policy',
      'iam_authorization','iam_session','iam_audit','iam_audit_archive','public'] LOOP
    IF has_schema_privilege('securegate_iam_runtime',schema_name,'CREATE') THEN
      RAISE EXCEPTION 'Unexpected runtime schema CREATE privilege (including PUBLIC)';
    END IF;
  END LOOP;
  IF has_database_privilege('securegate_iam_runtime',current_database(),'CREATE')
      OR has_table_privilege('securegate_iam_runtime','public.flyway_schema_history','SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
      OR has_table_privilege('securegate_iam_runtime','iam_audit.event_log','SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
      OR has_table_privilege('securegate_iam_runtime','iam_audit.event_receipts','SELECT,INSERT,UPDATE,DELETE,TRUNCATE')
      OR has_table_privilege('securegate_iam_runtime','iam_audit.outbox_operations','INSERT,UPDATE,DELETE,TRUNCATE')
      OR has_column_privilege('securegate_iam_runtime','iam_audit.outbox_events','payload','UPDATE')
      OR has_function_privilege('securegate_iam_runtime','iam_audit.archive_event_month(date,integer)','EXECUTE')
      OR has_function_privilege('securegate_iam_runtime','iam_audit.maintain_event_partitions(integer)','EXECUTE')
      OR has_function_privilege('securegate_iam_runtime','iam_audit.set_outbox_quarantine(uuid,boolean)','EXECUTE') THEN
    RAISE EXCEPTION 'Unexpected inherited runtime database privileges';
  END IF;
END;
$$;
COMMIT;
