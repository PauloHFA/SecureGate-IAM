package com.securegate.iam.shared.infrastructure;

import com.securegate.iam.shared.application.port.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public class JdbcUnitOfWork implements UnitOfWork {
  private final TransactionTemplate transactions;

  public JdbcUnitOfWork(TransactionTemplate transactions) {
    this.transactions = transactions;
  }

  @Override
  public <T> T execute(Supplier<T> operation) {
    return transactions.execute(status -> operation.get());
  }
}
