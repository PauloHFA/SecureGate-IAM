CREATE TABLE iam_authorization.refresh_families (
  authorization_id varchar(100) PRIMARY KEY REFERENCES iam_authorization.oauth2_authorization(id) ON DELETE CASCADE,
  registered_client_id varchar(100) NOT NULL REFERENCES iam_authorization.oauth2_registered_client(id),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz
);
CREATE TABLE iam_authorization.refresh_history (
  token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[a-f0-9]{64}$'),
  authorization_id varchar(100) NOT NULL REFERENCES iam_authorization.refresh_families(authorization_id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX refresh_history_family_idx ON iam_authorization.refresh_history(authorization_id);
CREATE UNIQUE INDEX refresh_history_current_idx ON iam_authorization.refresh_history(authorization_id) WHERE consumed_at IS NULL;
