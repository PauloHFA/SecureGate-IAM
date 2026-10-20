-- Authorization records only; Vault credential issuance belongs to 9B2.
CREATE TABLE iam_authorization.privileged_requests (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES iam_identity.users(id),
  target VARCHAR(128) NOT NULL CHECK (target='database:diagnostics'),
  justification VARCHAR(500) NOT NULL CHECK (length(trim(justification)) BETWEEN 10 AND 500),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  CHECK (expires_at>created_at AND expires_at<=created_at+INTERVAL '5 minutes'),
  CHECK (revoked_at IS NULL OR revoked_at>=created_at)
);
CREATE INDEX privileged_requests_owner ON iam_authorization.privileged_requests(user_id,id);
