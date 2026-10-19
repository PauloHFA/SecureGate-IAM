-- Existing contexts are not newly authenticated logins and must not emit retroactive facts.
ALTER TABLE iam_policy.browser_contexts
  ADD COLUMN login_assessed BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN iam_policy.browser_contexts.login_assessed IS
  'One committed login assessment per server sid; new producer inserts false and claims it in its transaction';
