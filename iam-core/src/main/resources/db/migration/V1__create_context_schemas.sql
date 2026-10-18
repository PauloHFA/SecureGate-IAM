CREATE SCHEMA iam_identity;
CREATE SCHEMA iam_access;
CREATE SCHEMA iam_policy;
CREATE SCHEMA iam_authorization;
CREATE SCHEMA iam_audit;
CREATE SCHEMA iam_session;

COMMENT ON SCHEMA iam_identity IS 'Identity context: users, credentials and MFA';
COMMENT ON SCHEMA iam_access IS 'Access context: roles, permissions and groups';
COMMENT ON SCHEMA iam_policy IS 'Policy context: conditional access and risk';
COMMENT ON SCHEMA iam_authorization IS 'Authorization context: OAuth and OIDC';
COMMENT ON SCHEMA iam_audit IS 'Audit context: security event records';
COMMENT ON SCHEMA iam_session IS 'Session context: sessions and devices';
