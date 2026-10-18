package com.securegate.iam.access.application;

import com.securegate.iam.access.domain.Permission;
import java.util.UUID;

public record PermissionView(UUID id, String resource, String action, String key) {
  static PermissionView from(Permission permission) {
    return new PermissionView(
        permission.id(), permission.resource(), permission.action(), permission.key());
  }
}
