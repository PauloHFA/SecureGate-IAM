package com.securegate.iam.authorization.presentation;

import com.securegate.iam.authorization.application.SigningUnavailableException;
import com.securegate.iam.shared.presentation.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(assignableTypes = JwksController.class)
public class SigningExceptionHandler {
  private final ProblemResponses problems;

  public SigningExceptionHandler(ProblemResponses problems) {
    this.problems = problems;
  }

  @ExceptionHandler(SigningUnavailableException.class)
  ResponseEntity<Object> unavailable(
      SigningUnavailableException exception, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", "5")
        .header("Cache-Control", "no-store")
        .body(problems.create(HttpStatus.SERVICE_UNAVAILABLE, request));
  }
}
