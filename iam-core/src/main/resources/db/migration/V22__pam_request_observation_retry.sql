ALTER TABLE iam_authorization.privileged_requests
  ADD COLUMN observation_attempts INTEGER NOT NULL DEFAULT 0 CHECK (observation_attempts BETWEEN 0 AND 30),
  ADD COLUMN next_observation_at TIMESTAMPTZ NOT NULL DEFAULT 'epoch',
  ADD COLUMN last_observation_failure_at TIMESTAMPTZ,
  ADD COLUMN last_observation_failure_code VARCHAR(32) CHECK
    (last_observation_failure_code IS NULL OR last_observation_failure_code='OBSERVATION_FAILED'),
  ADD CONSTRAINT pam_observation_failure_metadata CHECK
    ((last_observation_failure_at IS NULL) = (last_observation_failure_code IS NULL)),
  ADD CONSTRAINT pam_observation_retry_time CHECK
    (last_observation_failure_at IS NULL OR next_observation_at>=last_observation_failure_at);
CREATE INDEX pam_request_observation_schedule ON iam_authorization.privileged_requests(next_observation_at,expires_at,id)
  WHERE expiration_recorded_at IS NULL AND revoked_at IS NULL;
