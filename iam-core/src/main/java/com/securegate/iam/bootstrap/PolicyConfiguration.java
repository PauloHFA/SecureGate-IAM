package com.securegate.iam.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securegate.iam.access.application.port.EffectiveRoles;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.policy.application.PolicyService;
import com.securegate.iam.policy.application.port.PolicyDocuments;
import com.securegate.iam.policy.application.port.PolicyRepository;
import com.securegate.iam.policy.infrastructure.JdbcPolicyRepository;
import com.securegate.iam.policy.infrastructure.JsonPolicyDocuments;
import com.securegate.iam.shared.application.port.UnitOfWork;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class PolicyConfiguration {
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
      UnitOfWork transactions) {
    return new PolicyService(policies, documents, identities, roles, audit, transactions);
  }
}
