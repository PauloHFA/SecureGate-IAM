-- Spring Authorization Server 1.5.8 JDBC schemas, adapted as directed upstream:
-- PostgreSQL uses text for blob columns and timestamptz for timestamps.
-- Source: https://github.com/spring-projects/spring-authorization-server/tree/1.5.8/oauth2-authorization-server/src/main/resources/org/springframework/security/oauth2/server/authorization
CREATE TABLE iam_authorization.oauth2_registered_client (
  id varchar(100) PRIMARY KEY,
  client_id varchar(100) NOT NULL UNIQUE,
  client_id_issued_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  client_secret varchar(200),
  client_secret_expires_at timestamptz,
  client_name varchar(200) NOT NULL,
  client_authentication_methods varchar(1000) NOT NULL,
  authorization_grant_types varchar(1000) NOT NULL,
  redirect_uris varchar(1000),
  post_logout_redirect_uris varchar(1000),
  scopes varchar(1000) NOT NULL,
  client_settings varchar(2000) NOT NULL,
  token_settings varchar(2000) NOT NULL
);
CREATE TABLE iam_authorization.oauth2_authorization (
  id varchar(100) PRIMARY KEY,
  registered_client_id varchar(100) NOT NULL REFERENCES iam_authorization.oauth2_registered_client(id),
  principal_name varchar(200) NOT NULL,
  authorization_grant_type varchar(100) NOT NULL,
  authorized_scopes varchar(1000),
  attributes text,
  state varchar(500),
  authorization_code_value text,
  authorization_code_issued_at timestamptz,
  authorization_code_expires_at timestamptz,
  authorization_code_metadata text,
  access_token_value text,
  access_token_issued_at timestamptz,
  access_token_expires_at timestamptz,
  access_token_metadata text,
  access_token_type varchar(100),
  access_token_scopes varchar(1000),
  oidc_id_token_value text,
  oidc_id_token_issued_at timestamptz,
  oidc_id_token_expires_at timestamptz,
  oidc_id_token_metadata text,
  refresh_token_value text,
  refresh_token_issued_at timestamptz,
  refresh_token_expires_at timestamptz,
  refresh_token_metadata text,
  user_code_value text,
  user_code_issued_at timestamptz,
  user_code_expires_at timestamptz,
  user_code_metadata text,
  device_code_value text,
  device_code_issued_at timestamptz,
  device_code_expires_at timestamptz,
  device_code_metadata text
);
CREATE INDEX oauth_authorization_client_idx ON iam_authorization.oauth2_authorization(registered_client_id);
CREATE TABLE iam_authorization.oauth2_authorization_consent (
  registered_client_id varchar(100) NOT NULL REFERENCES iam_authorization.oauth2_registered_client(id),
  principal_name varchar(200) NOT NULL,
  authorities varchar(1000) NOT NULL,
  PRIMARY KEY (registered_client_id, principal_name)
);
