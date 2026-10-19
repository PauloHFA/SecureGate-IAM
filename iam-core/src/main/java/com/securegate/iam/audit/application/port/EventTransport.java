package com.securegate.iam.audit.application.port;

/** ACKED means broker confirmation and no mandatory return, not consumer completion. */
public interface EventTransport {
  Outcome publish(OutboxDelivery.Claim claim);

  enum Outcome {
    ACKED,
    UNROUTABLE,
    NACK,
    CONFIRM_TIMEOUT,
    BROKER_UNAVAILABLE,
    INVALID_EVENT
  }
}
