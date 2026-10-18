package com.securegate.iam.shared.presentation;

import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private final ProblemResponses problems;

  public GlobalExceptionHandler(ProblemResponses problems) {
    this.problems = problems;
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
    return new ResponseEntity<>(problems.create(status, servletRequest), headers, status);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception exception, HttpServletRequest request) {
    // Do not log arbitrary exception messages or payloads, which may contain secrets.
    LOG.error("Unhandled request failure (type={})", exception.getClass().getSimpleName());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(problems.create(HttpStatus.INTERNAL_SERVER_ERROR, request));
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<Object> notFound(
      ResourceNotFoundException exception, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(problems.create(HttpStatus.NOT_FOUND, request));
  }

  @ExceptionHandler({
    ConcurrentUpdateException.class,
    DataIntegrityViolationException.class,
    ConcurrencyFailureException.class
  })
  public ResponseEntity<Object> conflict(RuntimeException exception, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(problems.create(HttpStatus.CONFLICT, request));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Object> invalid(
      IllegalArgumentException exception, HttpServletRequest request) {
    return ResponseEntity.badRequest().body(problems.create(HttpStatus.BAD_REQUEST, request));
  }
}
