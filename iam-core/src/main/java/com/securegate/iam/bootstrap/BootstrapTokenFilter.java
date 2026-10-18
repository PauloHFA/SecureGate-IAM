package com.securegate.iam.bootstrap;

import com.securegate.iam.shared.presentation.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BootstrapTokenFilter extends OncePerRequestFilter {
  private final byte[] expectedDigest;
  private final ProblemResponses problems;

  public BootstrapTokenFilter(String token, ProblemResponses problems) {
    this.expectedDigest = digest(token);
    this.problems = problems;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getServletPath().equals("/api/admin")
        && !request.getServletPath().startsWith("/api/admin/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var headers = Collections.list(request.getHeaders("Authorization"));
    String header = headers.size() == 1 ? headers.getFirst() : "";
    if (header.length() > 512
        || !header.regionMatches(true, 0, "Bearer ", 0, 7)
        || !MessageDigest.isEqual(expectedDigest, digest(header.substring(7)))) {
      problems.unauthorized(request, response);
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            "bootstrap-admin", null, List.of(new SimpleGrantedAuthority("BOOTSTRAP_ADMIN"))));
    var previous = SecurityContextHolder.getContext();
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }
}
