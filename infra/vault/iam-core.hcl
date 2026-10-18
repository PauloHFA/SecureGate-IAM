path "secret/data/securegate/iam-core" {
  capabilities = ["read"]
}

path "transit/sign/securegate-jwt" {
  capabilities = ["update"]
}

path "transit/keys/securegate-jwt" {
  capabilities = ["read"]
}
