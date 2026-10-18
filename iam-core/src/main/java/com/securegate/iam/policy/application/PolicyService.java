package com.securegate.iam.policy.application;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.access.application.port.EffectiveRoles;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.policy.application.port.PolicyDocuments;
import com.securegate.iam.policy.application.port.PolicyRepository;
import com.securegate.iam.policy.domain.EvaluationContext;
import com.securegate.iam.policy.domain.Policy;
import com.securegate.iam.policy.domain.PolicyDecision;
import com.securegate.iam.policy.domain.PolicyEvaluator;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.PageRequest;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PolicyService {
  private static final int MAX_CANDIDATES = 1000;
  private final PolicyRepository policies;
  private final PolicyDocuments documents;
  private final IdentityLookup identities;
  private final EffectiveRoles roles;
  private final AuditTrail audit;
  private final UnitOfWork transactions;
  private final PolicyEvaluator evaluator = new PolicyEvaluator();

  public PolicyService(
      PolicyRepository policies,
      PolicyDocuments documents,
      IdentityLookup identities,
      EffectiveRoles roles,
      AuditTrail audit,
      UnitOfWork transactions) {
    this.policies = policies;
    this.documents = documents;
    this.identities = identities;
    this.roles = roles;
    this.audit = audit;
    this.transactions = transactions;
  }

  public PolicyView create(
      String name, int priority, boolean enabled, String document, OperationContext context) {
    var policy =
        new Policy(UUID.randomUUID(), name, priority, enabled, 0, documents.decode(document));
    return transactions.execute(
        () -> {
          policies.create(policy);
          record("policy.created", policy, context);
          return view(policy);
        });
  }

  public PolicyView update(
      UUID id,
      String name,
      int priority,
      boolean enabled,
      String document,
      long version,
      OperationContext context) {
    var rule = documents.decode(document);
    return transactions.execute(
        () -> {
          var previous = required(id);
          if (version != previous.version()) {
            throw new ConcurrentUpdateException();
          }
          var replacement = previous.replace(name, priority, enabled, rule);
          policies.update(replacement, version);
          record("policy.updated", replacement, context);
          return view(replacement);
        });
  }

  public PolicyView get(UUID id) {
    return view(required(id));
  }

  public List<PolicyView> list(PageRequest page) {
    return policies.list(page).stream().map(this::view).toList();
  }

  /** Administrative preview: caller cannot supply roles, MFA proof or a trusted risk score. */
  public DecisionView evaluate(
      UUID userId, String resource, String action, Set<String> requestedScopes) {
    if (userId == null) {
      throw new IllegalArgumentException("User ID is required");
    }
    var input = new EvaluationContext(resource, action, Set.of(), requestedScopes);
    if (!identities.isActive(userId)) {
      return DecisionView.from(PolicyDecision.deny("IDENTITY_INACTIVE", List.of()));
    }
    var candidates = policies.candidates(resource, action, MAX_CANDIDATES + 1);
    if (candidates.size() > MAX_CANDIDATES) {
      return DecisionView.from(PolicyDecision.deny("POLICY_LIMIT_EXCEEDED", List.of()));
    }
    var context =
        new EvaluationContext(
            input.resource(),
            input.action(),
            Set.copyOf(roles.forUser(userId)),
            input.requestedScopes());
    return DecisionView.from(evaluator.evaluate(candidates, context));
  }

  private Policy required(UUID id) {
    return policies.find(id).orElseThrow(ResourceNotFoundException::new);
  }

  private PolicyView view(Policy policy) {
    return PolicyView.from(policy, documents.encode(policy.document()));
  }

  private void record(String event, Policy policy, OperationContext context) {
    audit.record(
        new AuditRecord(
            new SecurityEventType(event),
            policy.id().toString(),
            context,
            Map.of(
                "version",
                Long.toString(policy.version()),
                "enabled",
                Boolean.toString(policy.enabled()),
                "effect",
                policy.document().effect().name())));
  }
}
