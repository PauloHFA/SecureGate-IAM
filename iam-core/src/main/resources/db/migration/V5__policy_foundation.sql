CREATE TABLE iam_policy.policies (
  id UUID PRIMARY KEY,
  name VARCHAR(64) NOT NULL UNIQUE CHECK (name ~ '^[a-z][a-z0-9_-]{2,63}$'),
  priority INTEGER NOT NULL CHECK (priority BETWEEN 0 AND 1000),
  enabled BOOLEAN NOT NULL,
  version BIGINT NOT NULL CHECK (version >= 0 AND version < 9223372036854775807),
  document JSONB NOT NULL CHECK (
    jsonb_typeof(document) = 'object'
    AND document ? 'schemaVersion'
    AND document->>'schemaVersion' = '1'
    AND length(document::text) <= 8192
  ),
  created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
CREATE INDEX policies_enabled_target ON iam_policy.policies
  ((document->>'resource'), (document->>'action'), priority DESC, id) WHERE enabled;
