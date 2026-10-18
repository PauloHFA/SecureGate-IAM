package com.securegate.iam.authorization.presentation;

import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
public class ConsentController {
  private final ConsentRequests consents;

  public ConsentController(ConsentRequests consents) {
    this.consents = consents;
  }

  @GetMapping(value = "/oauth2/consent", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> consent(
      @RequestParam String client_id,
      @RequestParam String state,
      Principal principal,
      HttpServletRequest request) {
    var session = request.getSession(false);
    String sid = session == null ? null : (String) session.getAttribute(ConsentRequests.SESSION_ID);
    var view =
        consents
            .find(state, client_id, principal.getName(), sid)
            .orElseThrow(ResourceNotFoundException::new);
    var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    var html =
        new StringBuilder(
                "<!doctype html><html lang=\"pt-BR\"><meta charset=\"utf-8\"><title>Autorizar acesso</title><body><main><h1>Autorizar acesso</h1><p>")
            .append(escape(view.name()))
            .append(
                " solicita os seguintes acessos:</p><form method=\"post\" action=\"/oauth2/authorize\">")
            .append(hidden("client_id", view.clientId()))
            .append(hidden("state", view.state()))
            .append(hidden(csrf.getParameterName(), csrf.getToken()));
    for (String scope : view.scopes()) {
      html.append("<label><input type=\"checkbox\" name=\"scope\" value=\"")
          .append(escape(scope))
          .append("\" checked>")
          .append(escape(scope))
          .append("</label><br>");
    }
    html.append("<button type=\"submit\">Autorizar selecionados</button></form>")
        .append("<form method=\"post\" action=\"/oauth2/authorize\">")
        .append(hidden("client_id", view.clientId()))
        .append(hidden("state", view.state()))
        .append(hidden(csrf.getParameterName(), csrf.getToken()))
        .append("<button type=\"submit\">Negar acesso</button></form></main></body></html>");
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header(
            "Content-Security-Policy",
            "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        .header("Referrer-Policy", "no-referrer")
        .body(html.toString());
  }

  private static String hidden(String name, String value) {
    return "<input type=\"hidden\" name=\"" + escape(name) + "\" value=\"" + escape(value) + "\">";
  }

  private static String escape(String value) {
    return HtmlUtils.htmlEscape(value);
  }
}
