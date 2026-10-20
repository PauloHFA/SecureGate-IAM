# Runtime has no access to the owner's KV document or database configuration.
path "secret/data/securegate/iam-runtime" { capabilities = ["read"] }
path "database/creds/iam-runtime" { capabilities = ["read"] }
path "transit/sign/securegate-jwt" { capabilities = ["update"] }
path "transit/keys/securegate-jwt" { capabilities = ["read"] }
path "transit/encrypt/securegate-mfa" { capabilities = ["update"] }
path "transit/decrypt/securegate-mfa" { capabilities = ["update"] }
