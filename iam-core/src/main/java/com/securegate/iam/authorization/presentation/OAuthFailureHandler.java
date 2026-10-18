package com.securegate.iam.authorization.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

public final class OAuthFailureHandler implements AuthenticationFailureHandler {
  private final ObjectMapper json;

  public OAuthFailureHandler(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    String code =
        exception instanceof OAuth2AuthenticationException oauth
            ? oauth.getError().getErrorCode()
            : "invalid_client";
    int status =
        switch (code) {
          case "invalid_client" -> 401;
          case "temporarily_unavailable" -> 503;
          case "server_error" -> 500;
          default -> 400;
        };
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Pragma", "no-cache");
    if (status == 401) {
      response.setHeader("WWW-Authenticate", "Basic realm=\"oauth2/token\"");
    }
    if (status == 503) {
      response.setHeader("Retry-After", "5");
    }
    json.writeValue(response.getOutputStream(), Map.of("error", code));
  }
}
