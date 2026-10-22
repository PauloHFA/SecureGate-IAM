-- Logical deadline observations are independent of Vault revocation acknowledgement.
ALTER TABLE iam_authorization.privileged_requests
  ADD COLUMN origin_correlation_id VARCHAR(64),
  ADD COLUMN expiration_recorded_at TIMESTAMPTZ,
  ADD CONSTRAINT pam_request_correlation CHECK
    (origin_correlation_id IS NULL OR origin_correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
  ADD CONSTRAINT pam_request_expiration_observation CHECK
    (expiration_recorded_at IS NULL OR expiration_recorded_at>=expires_at);
ALTER TABLE iam_authorization.privileged_issuances
  ADD COLUMN origin_correlation_id VARCHAR(64),
  ADD COLUMN expiration_recorded_at TIMESTAMPTZ,
  ADD CONSTRAINT pam_issuance_correlation CHECK
    (origin_correlation_id IS NULL OR origin_correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
  ADD CONSTRAINT pam_issuance_expiration_observation CHECK
    (expiration_recorded_at IS NULL OR
      (expires_at IS NOT NULL AND expiration_recorded_at>=expires_at));
CREATE INDEX pam_requests_expiration_audit ON iam_authorization.privileged_requests(expires_at)
  WHERE expiration_recorded_at IS NULL AND revoked_at IS NULL;
