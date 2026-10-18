CREATE TABLE iam_identity.login_failures (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES iam_identity.users(id),
  occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX login_failures_user_time ON iam_identity.login_failures(user_id,occurred_at);

CREATE TABLE iam_policy.browser_devices (
  user_id UUID NOT NULL REFERENCES iam_identity.users(id),
  device_id UUID NOT NULL,
  trusted_until TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  PRIMARY KEY(user_id,device_id)
);
CREATE TABLE iam_policy.browser_contexts (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES iam_identity.users(id),
  identity_version BIGINT NOT NULL CHECK (identity_version >= 0),
  device_id UUID NOT NULL,
  remote_address INET,
  observed_at TIMESTAMPTZ NOT NULL,
  closed_at TIMESTAMPTZ,
  FOREIGN KEY(user_id,device_id) REFERENCES iam_policy.browser_devices(user_id,device_id)
);
CREATE INDEX browser_contexts_user_time ON iam_policy.browser_contexts(user_id,observed_at DESC);
CREATE TABLE iam_policy.resource_profiles (
  resource VARCHAR(128) PRIMARY KEY,
  sensitivity VARCHAR(16) NOT NULL CHECK (sensitivity IN ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE TABLE iam_policy.trusted_networks (
  id UUID PRIMARY KEY,
  network CIDR NOT NULL UNIQUE,
  enabled BOOLEAN NOT NULL DEFAULT true,
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
