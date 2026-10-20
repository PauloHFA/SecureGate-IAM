CREATE TABLE iam_authorization.privileged_issuances (
  request_id UUID PRIMARY KEY REFERENCES iam_authorization.privileged_requests(id),
  state VARCHAR(16) NOT NULL CHECK (state IN ('ISSUING','ISSUED','FAILED','REVOKING','REVOKED')),
  created_at TIMESTAMPTZ NOT NULL,
  lease_id VARCHAR(512) UNIQUE,
  expires_at TIMESTAMPTZ,
  CHECK ((lease_id IS NULL) = (expires_at IS NULL)),
  CHECK (state NOT IN ('ISSUED','REVOKING','REVOKED') OR lease_id IS NOT NULL)
);
CREATE INDEX privileged_issuances_cleanup ON iam_authorization.privileged_issuances(state,created_at);
