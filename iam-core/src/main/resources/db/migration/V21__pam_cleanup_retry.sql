-- Retry metadata never changes the credential deadline or terminal audit facts.
ALTER TABLE iam_authorization.privileged_issuances
  ADD COLUMN cleanup_attempts INTEGER NOT NULL DEFAULT 0 CHECK (cleanup_attempts BETWEEN 0 AND 30),
  ADD COLUMN next_cleanup_at TIMESTAMPTZ NOT NULL DEFAULT 'epoch',
  ADD COLUMN last_cleanup_failure_at TIMESTAMPTZ,
  ADD COLUMN last_cleanup_failure_code VARCHAR(32) CHECK
    (last_cleanup_failure_code IS NULL OR last_cleanup_failure_code='CLEANUP_FAILED'),
  ADD CONSTRAINT pam_cleanup_failure_metadata CHECK
    ((last_cleanup_failure_at IS NULL) = (last_cleanup_failure_code IS NULL)),
  ADD CONSTRAINT pam_cleanup_retry_time CHECK
    (last_cleanup_failure_at IS NULL OR next_cleanup_at>=last_cleanup_failure_at);
CREATE INDEX pam_cleanup_schedule ON iam_authorization.privileged_issuances(next_cleanup_at,created_at,request_id)
  WHERE state IN ('ISSUING','ISSUED','REVOKING');
