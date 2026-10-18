package com.securegate.iam.shared.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @AfterEach
  void cleanLoggingContext() {
    MDC.clear();
  }

  @Test
  void propagatesSafeIdAndRestoresThePreviousLoggingContext() throws Exception {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    request.addHeader(CorrelationIdFilter.HEADER, "client-123");
    MDC.put("correlationId", "outer-context");
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          assertEquals("client-123", MDC.get("correlationId"));
          assertEquals("client-123", req.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        });
    assertEquals("client-123", response.getHeader(CorrelationIdFilter.HEADER));
    assertEquals("outer-context", MDC.get("correlationId"));
  }

  @Test
  void replacesUnsafeIdWithoutLeakingItIntoTheLoggingContext() throws Exception {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    request.addHeader(CorrelationIdFilter.HEADER, "untrusted value\r\n");
    filter.doFilter(request, response, (req, res) -> {});
    UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER));
    assertNull(MDC.get("correlationId"));
  }

  @Test
  void clearsTheLoggingContextEvenWhenTheRequestFails() {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    assertThrows(
        ServletException.class,
        () ->
            filter.doFilter(
                request,
                response,
                (req, res) -> {
                  throw new ServletException("synthetic failure");
                }));
    assertNull(MDC.get("correlationId"));
  }
}
