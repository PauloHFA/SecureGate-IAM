CREATE TABLE iam_identity.mfa_factors (
  user_id UUID PRIMARY KEY REFERENCES iam_identity.users(id),
  id UUID NOT NULL UNIQUE,
  state VARCHAR(8) NOT NULL CHECK (state IN ('NONE','PENDING','ACTIVE')),
  ciphertext VARCHAR(2048),
  enrollment_session UUID,
  enrollment_version BIGINT,
  pending_expires_at TIMESTAMPTZ,
  last_counter BIGINT NOT NULL DEFAULT -1 CHECK (last_counter >= -1),
  failures INTEGER NOT NULL DEFAULT 0 CHECK (failures BETWEEN 0 AND 5),
  blocked_until TIMESTAMPTZ,
  CHECK ((state='NONE' AND ciphertext IS NULL) OR
         (state IN ('PENDING','ACTIVE') AND ciphertext LIKE 'vault:v%')),
  CHECK (state <> 'PENDING' OR
         (enrollment_session IS NOT NULL AND enrollment_version IS NOT NULL AND pending_expires_at IS NOT NULL))
);

CREATE TABLE iam_identity.mfa_recovery_codes (
  factor_id UUID NOT NULL REFERENCES iam_identity.mfa_factors(id),
  code_hash CHAR(64) NOT NULL,
  PRIMARY KEY (factor_id,code_hash)
);

-- A closed-session marker prevents in-flight requests from recreating a proof after logout.
CREATE TABLE iam_session.mfa_session_state (
  session_id UUID PRIMARY KEY,
  closed BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE iam_session.mfa_proofs (
  session_id UUID PRIMARY KEY REFERENCES iam_session.mfa_session_state(session_id),
  user_id UUID NOT NULL REFERENCES iam_identity.users(id),
  identity_version BIGINT NOT NULL,
  factor_id UUID NOT NULL REFERENCES iam_identity.mfa_factors(id),
  method VARCHAR(8) NOT NULL CHECK (method IN ('TOTP','RECOVERY')),
  verified_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > verified_at)
);
CREATE INDEX mfa_proofs_user ON iam_session.mfa_proofs(user_id);
