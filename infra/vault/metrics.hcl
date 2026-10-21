# Dedicated scrape identity. No KV, Transit, token management or default policy.
path "sys/metrics" {
  capabilities = ["read"]
}
