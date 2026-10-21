-- Administrator-only, opt-in provisioning; not a Flyway application migration.
\getenv metrics_password PG_METRICS_PASSWORD
\getenv metrics_database PG_METRICS_DATABASE
BEGIN;
SELECT NOT EXISTS (SELECT FROM pg_roles WHERE rolname='securegate_metrics') AS metrics_new \gset
\if :metrics_new
CREATE ROLE securegate_metrics LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
\endif
DO $$
DECLARE target OID;
BEGIN
  SELECT oid INTO target FROM pg_roles WHERE rolname='securegate_metrics';
  IF EXISTS (SELECT FROM pg_roles WHERE oid=target AND
      (NOT rolcanlogin OR NOT rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication
       OR rolbypassrls OR rolconnlimit<>3))
    OR EXISTS (SELECT FROM pg_auth_members WHERE member=target AND
      (roleid<>'pg_monitor'::regrole OR admin_option))
    OR EXISTS (SELECT FROM pg_shdepend WHERE refclassid='pg_authid'::regclass
      AND refobjid=target AND deptype='o') THEN
    RAISE EXCEPTION 'Unexpected metrics role attributes, memberships or ownership';
  END IF;
  IF has_database_privilege(target,current_database(),'CREATE')
    OR EXISTS (SELECT FROM pg_namespace n WHERE
      (n.nspname='public' OR n.nspname LIKE 'iam\_%') AND has_schema_privilege(target,n.oid,'CREATE'))
    OR EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE (n.nspname='public' OR n.nspname LIKE 'iam\_%') AND c.relkind IN ('r','p','v','m','f')
      AND (has_table_privilege(target,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
        OR has_any_column_privilege(target,c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))) THEN
    RAISE EXCEPTION 'Unexpected metrics role access to application data or DDL';
  END IF;
END;
$$;
GRANT pg_monitor TO securegate_metrics;
GRANT CONNECT ON DATABASE :"metrics_database" TO securegate_metrics;
ALTER ROLE securegate_metrics SET default_transaction_read_only=on;
ALTER ROLE securegate_metrics SET statement_timeout='5s';
ALTER ROLE securegate_metrics SET search_path=pg_catalog;
-- Password is interpolated by psql from a process environment value, never a CLI argument.
ALTER ROLE securegate_metrics PASSWORD :'metrics_password';
COMMIT;
