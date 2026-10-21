# Dev mode forces unauthenticated metrics on its first listener. The overlay moves
# that listener to container loopback:18200; only this authenticated listener is published.
listener "tcp" {
  address = "0.0.0.0:8200"
  cluster_address = "0.0.0.0:18201"
  tls_disable = true
  telemetry {
    unauthenticated_metrics_access = false
  }
}

telemetry {
  prometheus_retention_time = "1m"
  disable_hostname = true
}
