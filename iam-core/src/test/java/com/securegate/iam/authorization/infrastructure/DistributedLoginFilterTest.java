package com.securegate.iam.authorization.infrastructure;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securegate.iam.authorization.application.port.AuthenticationEnforcement;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DistributedLoginFilterTest {
  @Test
  void blocksWithoutRunningCredentialChainAndSanitizesUnavailable() throws Exception {
    var enforcement = mock(AuthenticationEnforcement.class);
    var filter = new DistributedLoginFilter(enforcement);
    var request = new MockHttpServletRequest("POST", "/login");
    request.setServletPath("/login");
    request.setRemoteAddr("127.0.0.1");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> fail("Credentials must not run"));
    assertEquals(429, response.getStatus());
    assertEquals("5", response.getHeader("Retry-After"));
    request.removeAttribute(filter.getClass().getName() + ".FILTERED");
    when(enforcement.ipAllowed("127.0.0.1"))
        .thenThrow(new IllegalStateException("synthetic secret detail"));
    response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> fail("Credentials must not run"));
    assertEquals(503, response.getStatus());
    assertFalse(response.getContentAsString().contains("synthetic"));
  }

  @Test
  void skipsGetAndContinuesOnlyWhenAllowed() throws Exception {
    var enforcement = mock(AuthenticationEnforcement.class);
    var filter = new DistributedLoginFilter(enforcement);
    var request = new MockHttpServletRequest("GET", "/login");
    request.setServletPath("/login");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> res.getWriter().write("page"));
    verifyNoInteractions(enforcement);
    assertEquals("page", response.getContentAsString());
    request = new MockHttpServletRequest("POST", "/login");
    request.setServletPath("/login");
    request.setRemoteAddr("127.0.0.1");
    when(enforcement.ipAllowed("127.0.0.1")).thenReturn(true);
    response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> res.getWriter().write("credentials"));
    assertEquals("credentials", response.getContentAsString());
    assertTrue(
        AuthenticationEnforcement.DISABLED.loginAllowed(
            java.util.UUID.randomUUID(), 0, "127.0.0.1"));
  }
}
