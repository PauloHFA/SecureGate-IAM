package com.securegate.iam.access.application.port;

import com.securegate.iam.access.application.Assignment;
import com.securegate.iam.access.domain.Group;
import com.securegate.iam.access.domain.Permission;
import com.securegate.iam.access.domain.Role;
import com.securegate.iam.shared.application.port.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AccessRepository {
  void createRole(Role role);

  void createGroup(Group group);

  void createPermission(Permission permission);

  Optional<Role> role(UUID id);

  Optional<Group> group(UUID id);

  Optional<Permission> permission(UUID id);

  List<Role> roles(PageRequest page);

  List<Group> groups(PageRequest page);

  List<Permission> permissions(PageRequest page);

  boolean assign(Assignment assignment, UUID left, UUID right, boolean grant);

  Set<String> effectivePermissions(UUID userId);
}
