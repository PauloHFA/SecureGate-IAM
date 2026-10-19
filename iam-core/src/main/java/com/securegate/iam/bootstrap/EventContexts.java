package com.securegate.iam.bootstrap;

import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.presentation.CorrelationIdFilter;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

final class EventContexts {
  private EventContexts() {}

  static OperationContext current() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var attributes = RequestContextHolder.getRequestAttributes();
    Object correlation =
        attributes instanceof ServletRequestAttributes servlet
            ? servlet.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE)
            : null;
    return new OperationContext(
        authentication == null ? "system" : authentication.getName(),
        correlation instanceof String id ? id : UUID.randomUUID().toString());
  }
}
