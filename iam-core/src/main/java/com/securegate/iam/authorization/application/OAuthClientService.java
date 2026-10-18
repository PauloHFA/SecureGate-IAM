package com.securegate.iam.authorization.application;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.authorization.application.port.ClientRegistry;
import com.securegate.iam.authorization.domain.BrowserClient;
import com.securegate.iam.authorization.domain.MachineClient;
import com.securegate.iam.identity.application.port.PasswordHasher;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class OAuthClientService {
  private final ClientRegistry clients;
  private final PasswordHasher passwords;
  private final AuditTrail audit;
  private final UnitOfWork transactions;
  private final SecureRandom random = new SecureRandom();

  public OAuthClientService(
      ClientRegistry clients, PasswordHasher passwords, AuditTrail audit, UnitOfWork transactions) {
    this.clients = clients;
    this.passwords = passwords;
    this.audit = audit;
    this.transactions = transactions;
  }

  public CreatedClient create(
      String clientId, String name, String audience, Set<String> scopes, OperationContext context) {
    var definition = new MachineClient(clientId, name, audience, scopes);
    return createInternal(definition, hash -> clients.create(definition, hash), context);
  }

  public CreatedClient createBrowser(
      String clientId,
      String name,
      String audience,
      Set<String> scopes,
      Set<String> redirectUris,
      OperationContext context) {
    var definition = new MachineClient(clientId, name, audience, scopes);
    var browser = new BrowserClient(definition, redirectUris);
    return createInternal(definition, hash -> clients.createBrowser(browser, hash), context);
  }

  private CreatedClient createInternal(
      MachineClient definition, Function<String, ClientView> persist, OperationContext context) {
    String clientId = definition.clientId();
    byte[] entropy = new byte[32];
    random.nextBytes(entropy);
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    String hash = passwords.hash(secret);
    return transactions.execute(
        () -> {
          if (clients.find(clientId).isPresent()) {
            throw new ConcurrentUpdateException();
          }
          ClientView view = persist.apply(hash);
          audit.record(
              new AuditRecord(
                  new SecurityEventType("oauth.client.created"),
                  "oauth-client:" + clientId,
                  context,
                  Map.of("clientId", clientId, "audience", definition.audience())));
          return new CreatedClient(view, secret);
        });
  }

  public ClientView get(String clientId) {
    return clients.find(clientId).orElseThrow(ResourceNotFoundException::new);
  }

  public record CreatedClient(ClientView client, String clientSecret) {
    @Override
    public String toString() {
      return "CreatedClient[REDACTED]";
    }
  }
}
