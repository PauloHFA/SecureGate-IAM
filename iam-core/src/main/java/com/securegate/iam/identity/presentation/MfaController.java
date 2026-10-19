package com.securegate.iam.identity.presentation;

import com.securegate.iam.authorization.application.port.AuthorizationContinuation;
import com.securegate.iam.authorization.application.port.ConsentRequests;
import com.securegate.iam.identity.application.MfaService;
import com.securegate.iam.identity.application.MfaService.Result;
import com.securegate.iam.identity.application.MfaService.Session;
import com.securegate.iam.identity.application.port.AuthenticatedIdentity;
import com.securegate.iam.identity.application.port.MfaSecretProtector;
import com.securegate.iam.shared.presentation.OperationContexts;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
@ConditionalOnProperty(name = "iam.authorization.enabled", havingValue = "true")
public class MfaController {
  private final MfaService mfa;
  private final AuthorizationContinuation continuations;

  public MfaController(MfaService mfa, AuthorizationContinuation continuations) {
    this.mfa = mfa;
    this.continuations = continuations;
  }

  @GetMapping(value = "/mfa", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> page(Authentication authentication, HttpServletRequest request) {
    var status = mfa.status(session(authentication, request));
    String body = "<p>Estado: " + escape(status.state()) + "</p>";
    if ("ACTIVE".equals(status.state())) {
      body += "<p>Códigos de recuperação restantes: " + status.recoveryRemaining() + "</p>";
      if (status.proofExpiresAt() != null) {
        body += "<p>Step-up válido até " + status.proofExpiresAt() + "</p>";
      }
      body +=
          form(
              request,
              "/mfa/verify",
              "<label>Método <select name=\"method\"><option>TOTP</option><option>RECOVERY</option></select></label><label>Código <input name=\"code\" maxlength=\"22\" autocomplete=\"one-time-code\" required></label>",
              "Verificar");
    } else {
      body +=
          "<p>Para cadastrar, entre novamente há menos de cinco minutos e confirme sua senha.</p>";
      body +=
          form(
              request,
              "/mfa/enroll",
              "<label>Senha atual <input type=\"password\" name=\"password\" maxlength=\"128\" autocomplete=\"current-password\" required></label>",
              "Iniciar cadastro");
      if ("PENDING".equals(status.state())) {
        body += confirmation(request);
      }
    }
    return html(200, body + continuation(authentication, request));
  }

  @PostMapping(value = "/mfa/enroll", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> enroll(
      Authentication authentication, HttpServletRequest request, @RequestParam String password) {
    Session session = session(authentication, request);
    Result result = mfa.enroll(session, password, OperationContexts.from(authentication, request));
    if (!"SUCCESS".equals(result.outcome())) {
      return rejected(result);
    }
    String uri =
        "otpauth://totp/"
            + URLEncoder.encode("SecureGate:" + session.userId(), StandardCharsets.UTF_8)
            + "?secret="
            + result.secret()
            + "&issuer=SecureGate&algorithm=SHA1&digits=6&period=30";
    return html(
        200,
        "<p>Adicione esta chave manualmente ao autenticador. Ela será exibida apenas nesta resposta.</p><code id=\"totp-secret\">"
            + escape(result.secret())
            + "</code><p>URI de configuração:</p><code>"
            + escape(uri)
            + "</code><p>Confirme um código em até cinco minutos nesta sessão.</p>"
            + confirmation(request));
  }

  @PostMapping(value = "/mfa/confirm", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> confirm(
      Authentication authentication, HttpServletRequest request, @RequestParam String code) {
    Result result =
        mfa.confirm(
            session(authentication, request),
            code,
            OperationContexts.from(authentication, request));
    if (!"SUCCESS".equals(result.outcome())) {
      return rejected(result);
    }
    StringBuilder codes =
        new StringBuilder(
            "<p>MFA ativado. Guarde estes códigos em local seguro. Cada código pode ser usado uma vez; esta lista não será exibida novamente.</p><ul>");
    for (String recovery : result.recoveryCodes()) {
      codes
          .append("<li><code class=\"recovery-code\">")
          .append(escape(recovery))
          .append("</code></li>");
    }
    return html(200, codes.append("</ul>").toString() + continuation(authentication, request));
  }

  @PostMapping(value = "/mfa/verify", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> verify(
      Authentication authentication,
      HttpServletRequest request,
      @RequestParam String method,
      @RequestParam String code) {
    Result result =
        mfa.verify(
            session(authentication, request),
            method,
            code,
            OperationContexts.from(authentication, request));
    return "SUCCESS".equals(result.outcome())
        ? html(
            200,
            "<p>Fator comprovado para esta sessão por cinco minutos.</p>"
                + continuation(authentication, request))
        : rejected(result);
  }

  @ExceptionHandler(MfaSecretProtector.Unavailable.class)
  ResponseEntity<String> unavailable() {
    return html(503, "<p>Serviço de MFA indisponível. Tente novamente depois.</p>");
  }

  @ExceptionHandler(MfaService.StaleSession.class)
  ResponseEntity<String> stale() {
    return html(401, "<p>Entre novamente para continuar.</p>");
  }

  private static Session session(Authentication authentication, HttpServletRequest request) {
    var session = request.getSession(false);
    if (authentication == null
        || !(authentication.getPrincipal() instanceof AuthenticatedIdentity identity)
        || session == null) {
      throw new MfaService.StaleSession();
    }
    try {
      return new Session(
          UUID.fromString(identity.getName()),
          identity.version(),
          UUID.fromString((String) session.getAttribute(ConsentRequests.SESSION_ID)),
          Instant.parse((String) session.getAttribute("securegate.auth.time")));
    } catch (RuntimeException exception) {
      throw new MfaService.StaleSession();
    }
  }

  private static String confirmation(HttpServletRequest request) {
    return form(
        request,
        "/mfa/confirm",
        "<label>Código TOTP <input name=\"code\" inputmode=\"numeric\" pattern=\"[0-9]{6}\" minlength=\"6\" maxlength=\"6\" autocomplete=\"one-time-code\" required></label>",
        "Ativar MFA");
  }

  private String continuation(Authentication authentication, HttpServletRequest request) {
    Session current = session(authentication, request);
    return continuations
        .current(current.userId(), current.version(), current.sid())
        .map(
            view -> {
              String text =
                  "<p>Verificação adicional para autorizar " + escape(view.clientName()) + ".</p>";
              return view.ready()
                  ? text
                      + form(
                          request,
                          "/oauth2/continue",
                          "<input type=\"hidden\" name=\"ticket\" value=\""
                              + escape(view.ticket())
                              + "\">",
                          "Continuar autorização")
                  : text;
            })
        .orElse("");
  }

  private static String form(
      HttpServletRequest request, String action, String fields, String label) {
    var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return "<form method=\"post\" action=\""
        + action
        + "\"><input type=\"hidden\" name=\""
        + escape(csrf.getParameterName())
        + "\" value=\""
        + escape(csrf.getToken())
        + "\">"
        + fields
        + "<button type=\"submit\">"
        + label
        + "</button></form>";
  }

  private static ResponseEntity<String> rejected(Result result) {
    return html(
        "LOCKED".equals(result.outcome()) ? 429 : 400,
        "<p>Não foi possível verificar. Confira os dados e a validade do cadastro. Após cinco falhas, aguarde cinco minutos.</p>");
  }

  private static String escape(String value) {
    return HtmlUtils.htmlEscape(value);
  }

  private static ResponseEntity<String> html(int status, String body) {
    return ResponseEntity.status(status)
        .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
        .header("Cache-Control", "no-store")
        .header("Referrer-Policy", "no-referrer")
        .header(
            "Content-Security-Policy",
            "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        .body(
            "<!doctype html><html lang=\"pt-BR\"><meta charset=\"utf-8\"><title>MFA SecureGate</title><body><main><h1>MFA</h1>"
                + body
                + "<p><a href=\"/mfa\">MFA</a> · <a href=\"/login\">Sessão</a></p></main></body></html>");
  }
}
