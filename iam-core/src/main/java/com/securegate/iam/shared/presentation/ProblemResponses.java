package com.securegate.iam.shared.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

@Component
public class ProblemResponses {
  private final ObjectMapper objectMapper;

  public ProblemResponses(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public ProblemDetail create(HttpStatusCode status, HttpServletRequest request) {
    String detail =
        switch (status.value()) {
          case 400 -> "The request is invalid.";
          case 401 -> "Administrative authentication is required.";
          case 403 -> "Access to this resource is denied.";
          case 404 -> "The requested resource was not found.";
          case 405 -> "The request method is not supported.";
          case 409 -> "The request conflicts with the current resource state.";
          default ->
              status.is5xxServerError()
                  ? "An internal error occurred. Use the correlation ID when contacting support."
                  : "The request could not be processed.";
        };
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    HttpStatus knownStatus = HttpStatus.resolve(status.value());
    if (knownStatus != null) {
      problem.setTitle(knownStatus.getReasonPhrase());
    }
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("correlationId", request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
    return problem;
  }

  public void unauthorized(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    write(HttpStatus.UNAUTHORIZED, request, response);
  }

  public void forbidden(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    write(HttpStatus.FORBIDDEN, request, response);
  }

  private void write(HttpStatus status, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), create(status, request));
  }
}
