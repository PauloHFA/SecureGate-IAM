package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.access.application.port.EffectiveRoles;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.LoginRiskHistory;
import com.securegate.iam.identity.infrastructure.JdbcLoginRiskHistory;
import com.securegate.iam.policy.application.PolicyService;
import com.securegate.iam.policy.application.RiskAdministration;
import com.securegate.iam.policy.application.port.PolicyDocuments;
import com.securegate.iam.policy.application.port.PolicyRepository;
import com.securegate.iam.policy.application.port.RiskEvidence;
import com.securegate.iam.policy.application.port.RiskSignalsProvider;
import com.securegate.iam.policy.infrastructure.JdbcPolicyRepository;
import com.securegate.iam.policy.infrastructure.JdbcRiskEvidence;
import com.securegate.iam.policy.infrastructure.JsonPolicyDocuments;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class PolicyConfiguration {
  @Bean
  Clock policyClock() {
    return Clock.systemUTC();
  }

  @Bean
  RiskSignalsProvider riskSignalsProvider(RiskEvidence evidence) {
    return evidence::collect;
  }

  @Bean
  LoginRiskHistory loginRiskHistory(
      JdbcTemplate jdbc,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value("${iam.authorization.enabled:false}")
          boolean enabled) {
    return new JdbcLoginRiskHistory(jdbc, clock, enabled);
  }

  @Bean
  RiskEvidence riskEvidence(
      JdbcTemplate jdbc,
      Clock clock,
      LoginRiskHistory history,
      IdentityLookup identities,
      UnitOfWork transactions) {
    return new JdbcRiskEvidence(jdbc, clock, history, identities, transactions);
  }

  @Bean
  RiskAdministration riskAdministration(
      RiskEvidence evidence, IdentityLookup identities, UnitOfWork transactions, AuditTrail audit) {
    return new RiskAdministration(evidence, identities, transactions, audit);
  }

  @Bean
  PolicyDocuments policyDocuments(ObjectMapper json) {
    return new JsonPolicyDocuments(json);
  }

  @Bean
  PolicyRepository policyRepository(JdbcTemplate jdbc, PolicyDocuments documents) {
    return new JdbcPolicyRepository(jdbc, documents);
  }

  @Bean
  PolicyService policyService(
      PolicyRepository policies,
      PolicyDocuments documents,
      IdentityLookup identities,
      EffectiveRoles roles,
      AuditTrail audit,
      UnitOfWork transactions,
      RiskSignalsProvider signals,
      Clock clock) {
    return new PolicyService(
        policies, documents, identities, roles, audit, transactions, signals, clock);
  }
}
