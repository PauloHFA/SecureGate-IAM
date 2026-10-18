package com.securegate.iam.shared.application.port;

import java.util.function.Supplier;

public interface UnitOfWork {
  <T> T execute(Supplier<T> operation);
}
