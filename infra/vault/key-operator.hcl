# Política operacional separada. Não é atribuída às aplicações.
path "transit/keys/securegate-jwt" {
  capabilities = ["read"]
}

path "transit/keys/securegate-jwt/rotate" {
  capabilities = ["update"]
}

path "transit/keys/securegate-mfa" {
  capabilities = ["read"]
}

path "transit/keys/securegate-mfa/rotate" {
  capabilities = ["update"]
}
