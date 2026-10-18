package com.securegate.iam.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.shared.presentation.ProblemResponses;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class BootstrapTokenFilterTest {
  private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
  private final BootstrapTokenFilter filter =
      new BootstrapTokenFilter(token, new ProblemResponses(new ObjectMapper()));

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private MockHttpServletRequest request() {
    var request = new MockHttpServletRequest();
    request.setServletPath("/api/admin/users");
    return request;
  }

  @Test
  void acceptsBearerWithoutRetainingTheCredentialAndRestoresContext() throws Exception {
    var request = request();
    request.addHeader("Authorization", "Bearer " + token);
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, res) -> {
          var authentication = SecurityContextHolder.getContext().getAuthentication();
          assertEquals("bootstrap-admin", authentication.getName());
          assertNull(authentication.getCredentials());
          assertTrue(
              authentication.getAuthorities().stream()
                  .anyMatch(authority -> authority.getAuthority().equals("BOOTSTRAP_ADMIN")));
        });
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void rejectsAbsentWrongAndDuplicateAuthorizationHeaders() throws Exception {
    for (String header :
        new String[] {
          "", "Basic invalid", "Bearer invalid", "Bearer ", "Bearer " + "x".repeat(513)
        }) {
      var request = request();
      if (!header.isEmpty()) {
        request.addHeader("Authorization", header);
      }
      var response = new MockHttpServletResponse();
      filter.doFilter(
          request, response, (req, res) -> fail("Unauthorized request reached the API"));
      assertEquals(401, response.getStatus());
      assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
      assertFalse(response.getContentAsString().contains(token));
    }
    var duplicate = request();
    duplicate.addHeader("Authorization", "Bearer " + token);
    duplicate.addHeader("Authorization", "Bearer " + token);
    var response = new MockHttpServletResponse();
    filter.doFilter(duplicate, response, (req, res) -> fail("Duplicate credentials accepted"));
    assertEquals(401, response.getStatus());
  }

  @Test
  void leavesNonAdministrativeRoutesAlone() throws Exception {
    var request = request();
    request.setServletPath("/actuator/health");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> res.getWriter().write("health"));
    assertEquals("health", response.getContentAsString());
  }

  @Test
  void validatesBootstrapConfigurationAndRedactsItsRepresentation() {
    assertFalse(new BootstrapAdminProperties(false, "").enabled());
    assertTrue(new BootstrapAdminProperties(true, token).enabled());
    assertFalse(new BootstrapAdminProperties(true, token).toString().contains(token));
    assertThrows(IllegalArgumentException.class, () -> new BootstrapAdminProperties(true, null));
    assertThrows(IllegalArgumentException.class, () -> new BootstrapAdminProperties(true, "short"));
    assertThrows(
        IllegalArgumentException.class, () -> new BootstrapAdminProperties(true, "x".repeat(257)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BootstrapAdminProperties(true, "x".repeat(32) + " "));
  }
}
