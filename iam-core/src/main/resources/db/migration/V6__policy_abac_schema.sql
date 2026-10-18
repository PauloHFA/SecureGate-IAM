ALTER TABLE iam_policy.policies DROP CONSTRAINT policies_document_check;
ALTER TABLE iam_policy.policies ADD CONSTRAINT policies_document_check CHECK (
  jsonb_typeof(document) = 'object'
  AND document ? 'schemaVersion'
  AND document->>'schemaVersion' IN ('1', '2')
  AND length(document::text) <= 8192
);
