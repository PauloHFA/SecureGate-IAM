package com.securegate.iam.access.application.port;

import java.util.List;
import java.util.UUID;

public interface EffectiveRoles {
  List<String> forUser(UUID id);
}
