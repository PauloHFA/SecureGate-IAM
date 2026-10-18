package com.securegate.iam.authorization.infrastructure;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/** Scope upstream unqualified SQL to this context, while sharing the existing transaction. */
public final class AuthorizationJdbcDataSource extends DelegatingDataSource {
  public AuthorizationJdbcDataSource(DataSource dataSource) {
    super(new TransactionAwareDataSourceProxy(dataSource));
  }

  @Override
  public Connection getConnection() throws SQLException {
    Connection connection = super.getConnection();
    String previous;
    try {
      previous = connection.getSchema();
      connection.setSchema("iam_authorization");
    } catch (SQLException exception) {
      connection.close();
      throw exception;
    }
    AtomicBoolean closed = new AtomicBoolean();
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("close")) {
                if (!closed.compareAndSet(false, true)) {
                  return null;
                }
                try {
                  connection.setSchema(previous);
                } finally {
                  connection.close();
                }
                return null;
              }
              if (method.getName().equals("equals")) {
                return proxy == arguments[0];
              }
              if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
              }
              try {
                return method.invoke(connection, arguments);
              } catch (InvocationTargetException exception) {
                throw exception.getCause();
              }
            });
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    throw new SQLException("Per-call credentials are not supported.");
  }
}
