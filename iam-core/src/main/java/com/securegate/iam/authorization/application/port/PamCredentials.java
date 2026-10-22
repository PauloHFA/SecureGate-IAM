package com.securegate.iam.authorization.application.port;

public interface PamCredentials {
  Issued issue(int ttlSeconds);

  void revoke(String leaseId);

  interface Issued extends AutoCloseable {
    String leaseId();

    String username();

    String password();

    int ttlSeconds();

    void delivered();

    @Override
    void close();
  }
}
