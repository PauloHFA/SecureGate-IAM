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
          case 403 -> "Access to this resource is denied.";
          case 404 -> "The requested resource was not found.";
          case 405 -> "The request method is not supported.";
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

  public void forbidden(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), create(HttpStatus.FORBIDDEN, request));
  }
}
