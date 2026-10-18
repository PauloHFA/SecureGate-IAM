CREATE TABLE iam_identity.users (
  id UUID PRIMARY KEY,
  email VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email)),
  display_name VARCHAR(120) NOT NULL CHECK (length(trim(display_name)) > 0),
  status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
CREATE TABLE iam_identity.credentials (
  user_id UUID PRIMARY KEY REFERENCES iam_identity.users(id),
  password_hash TEXT NOT NULL CHECK (password_hash LIKE '$argon2id$%'),
  changed_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
CREATE TABLE iam_access.roles (
  id UUID PRIMARY KEY,
  name VARCHAR(64) NOT NULL UNIQUE CHECK (name ~ '^[A-Z][A-Z0-9_]{0,63}$')
);
CREATE TABLE iam_access.groups (
  id UUID PRIMARY KEY,
  name VARCHAR(64) NOT NULL UNIQUE CHECK (name ~ '^[A-Z][A-Z0-9_]{0,63}$')
);
CREATE TABLE iam_access.permissions (
  id UUID PRIMARY KEY,
  resource VARCHAR(64) NOT NULL CHECK (resource ~ '^[a-z][a-z0-9_-]{0,63}$'),
  action VARCHAR(64) NOT NULL CHECK (action ~ '^[a-z][a-z0-9_-]{0,63}$'),
  UNIQUE (resource, action)
);
CREATE TABLE iam_access.role_permissions (
  role_id UUID NOT NULL REFERENCES iam_access.roles(id),
  permission_id UUID NOT NULL REFERENCES iam_access.permissions(id),
  PRIMARY KEY (role_id, permission_id)
);
CREATE TABLE iam_access.user_roles (
  user_id UUID NOT NULL REFERENCES iam_identity.users(id),
  role_id UUID NOT NULL REFERENCES iam_access.roles(id),
  PRIMARY KEY (user_id, role_id)
);
CREATE TABLE iam_access.group_members (
  group_id UUID NOT NULL REFERENCES iam_access.groups(id),
  user_id UUID NOT NULL REFERENCES iam_identity.users(id),
  PRIMARY KEY (group_id, user_id)
);
CREATE INDEX group_members_user_idx ON iam_access.group_members(user_id);
CREATE TABLE iam_access.group_roles (
  group_id UUID NOT NULL REFERENCES iam_access.groups(id),
  role_id UUID NOT NULL REFERENCES iam_access.roles(id),
  PRIMARY KEY (group_id, role_id)
);
CREATE TABLE iam_audit.security_events (
  id UUID PRIMARY KEY,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
  actor VARCHAR(200) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  resource VARCHAR(100) NOT NULL,
  correlation_id VARCHAR(64) NOT NULL,
  details JSONB NOT NULL CHECK (jsonb_typeof(details) = 'object')
);
CREATE INDEX security_events_resource_idx ON iam_audit.security_events(resource, occurred_at DESC);
CREATE FUNCTION iam_audit.reject_event_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Audit records are append-only' USING ERRCODE = '42501';
END;
$$;
CREATE TRIGGER security_events_append_only BEFORE UPDATE OR DELETE ON iam_audit.security_events
FOR EACH ROW EXECUTE FUNCTION iam_audit.reject_event_mutation();
CREATE TRIGGER security_events_no_truncate BEFORE TRUNCATE ON iam_audit.security_events
FOR EACH STATEMENT EXECUTE FUNCTION iam_audit.reject_event_mutation();
