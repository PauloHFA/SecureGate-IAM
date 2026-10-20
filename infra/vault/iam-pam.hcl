# Separate mount: this AppRole cannot read runtime credentials, KV, Transit or configuration.
path "pam-database/creds/diagnostics-*" { capabilities = ["read"] }
path "sys/leases/revoke/pam-database/creds/diagnostics-*" {
  capabilities = ["update"]
  allowed_parameters = { "sync" = [true] }
}
