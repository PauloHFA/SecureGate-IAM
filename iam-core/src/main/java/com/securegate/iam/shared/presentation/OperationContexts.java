package com.securegate.iam.shared.presentation;

import com.securegate.iam.shared.application.port.OperationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

public final class OperationContexts {
  public static OperationContext from(Authentication authentication, HttpServletRequest request) {
    return new OperationContext(
        authentication.getName(), (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
  }

  private OperationContexts() {}
}
