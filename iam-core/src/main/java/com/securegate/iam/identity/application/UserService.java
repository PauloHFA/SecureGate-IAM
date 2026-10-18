package com.securegate.iam.identity.application;

import com.securegate.events.SecurityEventType;
import com.securegate.iam.audit.application.port.AuditRecord;
import com.securegate.iam.audit.application.port.AuditTrail;
import com.securegate.iam.identity.application.port.CredentialRepository;
import com.securegate.iam.identity.application.port.IdentityLookup;
import com.securegate.iam.identity.application.port.PasswordHasher;
import com.securegate.iam.identity.application.port.UserRepository;
import com.securegate.iam.identity.domain.User;
import com.securegate.iam.identity.domain.UserStatus;
import com.securegate.iam.shared.application.ConcurrentUpdateException;
import com.securegate.iam.shared.application.ResourceNotFoundException;
import com.securegate.iam.shared.application.port.OperationContext;
import com.securegate.iam.shared.application.port.PageRequest;
import com.securegate.iam.shared.application.port.UnitOfWork;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class UserService implements IdentityLookup {
  private final UserRepository users;
  private final CredentialRepository credentials;
  private final PasswordHasher passwords;
  private final AuditTrail audit;
  private final UnitOfWork transactions;

  public UserService(
      UserRepository users,
      CredentialRepository credentials,
      PasswordHasher passwords,
      AuditTrail audit,
      UnitOfWork transactions) {
    this.users = users;
    this.credentials = credentials;
    this.passwords = passwords;
    this.audit = audit;
    this.transactions = transactions;
  }

  public UserView create(String email, String name, String password, OperationContext context) {
    User user = User.register(UUID.randomUUID(), email, name);
    String hash = hashPassword(password);
    return transactions.execute(
        () -> {
          users.create(user);
          credentials.create(user.id(), hash);
          record("identity.user.created", user, context);
          return UserView.from(user);
        });
  }

  public UserView get(UUID id) {
    return UserView.from(required(id));
  }

  public List<UserView> list(PageRequest page) {
    return users.list(page).stream().map(UserView::from).toList();
  }

  public UserView update(
      UUID id, String email, String name, long version, OperationContext context) {
    return transactions.execute(
        () -> {
          User updated = versioned(id, version).profile(email, name);
          users.update(updated, version);
          record("identity.user.updated", updated, context);
          return UserView.from(updated);
        });
  }

  public UserView status(UUID id, String status, long version, OperationContext context) {
    UserStatus next = UserStatus.valueOf(status);
    return transactions.execute(
        () -> {
          User previous = versioned(id, version);
          User updated = previous.transition(next);
          if (updated != previous) {
            users.update(updated, version);
            record("identity.user.statuschanged", updated, context);
          }
          return UserView.from(updated);
        });
  }

  public void password(UUID id, String password, long version, OperationContext context) {
    String hash = hashPassword(password);
    transactions.execute(
        () -> {
          User updated = versioned(id, version).credentialsChanged();
          users.update(updated, version);
          credentials.replace(id, hash);
          record("identity.user.passwordchanged", updated, context);
          return null;
        });
  }

  @Override
  public boolean exists(UUID id) {
    return users.find(id).isPresent();
  }

  @Override
  public boolean isActive(UUID id) {
    return users.find(id).map(user -> user.status() == UserStatus.ACTIVE).orElse(false);
  }

  private User required(UUID id) {
    return users.find(id).orElseThrow(ResourceNotFoundException::new);
  }

  @Override
  public boolean isCurrent(UUID id, long version) {
    return users
        .find(id)
        .map(user -> user.status() == UserStatus.ACTIVE && user.version() == version)
        .orElse(false);
  }

  private User versioned(UUID id, long version) {
    if (version < 0) {
      throw new IllegalArgumentException("Version is invalid");
    }
    User user = required(id);
    if (user.version() != version) {
      throw new ConcurrentUpdateException();
    }
    return user;
  }

  private String hashPassword(String password) {
    if (password == null
        || password.isBlank()
        || password.length() < 12
        || password.length() > 128) {
      throw new IllegalArgumentException("Password must have between 12 and 128 characters");
    }
    return passwords.hash(password);
  }

  private void record(String type, User user, OperationContext context) {
    audit.record(
        new AuditRecord(
            new SecurityEventType(type),
            user.id().toString(),
            context,
            Map.of("status", user.status().name(), "version", Long.toString(user.version()))));
  }
}
