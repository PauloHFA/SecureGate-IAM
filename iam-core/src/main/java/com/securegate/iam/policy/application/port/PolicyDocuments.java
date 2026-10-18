package com.securegate.iam.policy.application.port;

import com.securegate.iam.policy.domain.PolicyRule;

public interface PolicyDocuments {
  PolicyRule decode(String document);

  String encode(PolicyRule rule);
}
