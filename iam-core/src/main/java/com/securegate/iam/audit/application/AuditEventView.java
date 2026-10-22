package com.securegate.iam.audit.application;

import com.securegate.events.SecurityEvent;
import java.time.Instant;
import java.util.UUID;

public record AuditEventView(
    UUID id, Instant occurredAt, Instant receivedAt, SecurityEvent event) {}
