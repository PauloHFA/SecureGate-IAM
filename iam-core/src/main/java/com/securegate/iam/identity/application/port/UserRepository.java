package com.securegate.iam.identity.application.port;

import com.securegate.iam.identity.domain.User;
import com.securegate.iam.shared.application.port.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
  void create(User user);

  Optional<User> find(UUID id);

  List<User> list(PageRequest page);

  void update(User user, long expectedVersion);
}
