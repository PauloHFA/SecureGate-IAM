path "secret/data/securegate/iam-core" {
  capabilities = ["read"]
}

path "transit/sign/securegate-jwt" {
  capabilities = ["update"]
}

path "transit/keys/securegate-jwt" {
  capabilities = ["read"]
}

path "transit/encrypt/securegate-mfa" {
  capabilities = ["update"]
}

path "transit/decrypt/securegate-mfa" {
  capabilities = ["update"]
}
