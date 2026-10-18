package com.securegate.iam.authorization.presentation;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
public class LoginController {
  @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> login(HttpServletRequest request, Principal principal) {
    var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    String hidden =
        "<input type=\"hidden\" name=\""
            + HtmlUtils.htmlEscape(csrf.getParameterName())
            + "\" value=\""
            + HtmlUtils.htmlEscape(csrf.getToken())
            + "\">";
    String message =
        request.getParameter("error") == null
            ? ""
            : "<p role=\"alert\">Não foi possível entrar. Confira suas credenciais.</p>";
    String form =
        principal == null
            ? """
        <form method="post" action="/login">
        <label>E-mail <input type="email" name="username" maxlength="254" autocomplete="username" required></label><br>
        <label>Senha <input type="password" name="password" minlength="12" maxlength="128" autocomplete="current-password" required></label><br>
        %s<button type="submit">Entrar</button></form>
        """
                .formatted(hidden)
            : "<p>Sessão autenticada.</p><form method=\"post\" action=\"/logout\">"
                + hidden
                + "<button type=\"submit\">Sair</button></form>";
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header(
            "Content-Security-Policy",
            "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        .header("Referrer-Policy", "no-referrer")
        .body(
            "<!doctype html><html lang=\"pt-BR\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Entrar no SecureGate</title><body><main><h1>Entrar no SecureGate</h1>"
                + message
                + form
                + "</main></body></html>");
  }
}
