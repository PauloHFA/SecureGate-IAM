package com.securegate.iam.policy.application.port;

import com.securegate.iam.policy.domain.Policy;
import com.securegate.iam.shared.application.port.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository {
  void create(Policy policy);

  void update(Policy policy, long expectedVersion);

  Optional<Policy> find(UUID id);

  List<Policy> list(PageRequest page);

  List<Policy> candidates(String resource, String action, int limit);
}
