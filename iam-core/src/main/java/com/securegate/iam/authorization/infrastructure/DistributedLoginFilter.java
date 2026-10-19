package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.AuthenticationEnforcement;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** IP-only check after CSRF, before credential lookup. No account information is needed. */
public final class DistributedLoginFilter extends OncePerRequestFilter {
  private final AuthenticationEnforcement enforcement;

  public DistributedLoginFilter(AuthenticationEnforcement enforcement) {
    this.enforcement = enforcement;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || !"/login".equals(request.getServletPath());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    final boolean allowed;
    try {
      allowed = enforcement.ipAllowed(request.getRemoteAddr());
    } catch (RuntimeException unavailable) {
      unavailable(response);
      return;
    }
    if (!allowed) {
      write(response, 429, "rate_limited");
      return;
    }
    chain.doFilter(request, response);
  }

  public static void unavailable(HttpServletResponse response) throws IOException {
    write(response, 503, "temporarily_unavailable");
  }

  private static void write(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Retry-After", "5");
    response.getWriter().write("{\"error\":\"" + code + "\"}");
  }
}
