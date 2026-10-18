# Política operacional separada. Não é atribuída às aplicações.
path "transit/keys/securegate-jwt" {
  capabilities = ["read"]
}

path "transit/keys/securegate-jwt/rotate" {
  capabilities = ["update"]
}
