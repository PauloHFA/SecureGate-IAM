package com.securegate.iam.authorization.infrastructure;

import com.securegate.iam.authorization.application.port.ContinuationStore;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Single bounded pending authorization per local browser session. Reads never extend expiry. */
public final class SessionContinuationStore implements ContinuationStore {
  private static final String KEY = "securegate.authorization.continuation";

  private HttpSession session() {
    var attributes = RequestContextHolder.getRequestAttributes();
    return attributes instanceof ServletRequestAttributes servlet
        ? servlet.getRequest().getSession(false)
        : null;
  }

  @Override
  public void save(Pending pending) {
    var session = session();
    if (session == null) {
      throw new IllegalStateException("Browser session required.");
    }
    synchronized (session) {
      session.setAttribute(KEY, pending);
    }
  }

  @Override
  public Optional<Pending> load() {
    var session = session();
    if (session == null) {
      return Optional.empty();
    }
    synchronized (session) {
      return session.getAttribute(KEY) instanceof Pending pending
          ? Optional.of(pending)
          : Optional.empty();
    }
  }

  @Override
  public boolean remove(String ticket) {
    var session = session();
    if (session == null) {
      return false;
    }
    synchronized (session) {
      if (session.getAttribute(KEY) instanceof Pending pending && pending.ticket().equals(ticket)) {
        session.removeAttribute(KEY);
        return true;
      }
      return false;
    }
  }

  @Override
  public void clear() {
    var session = session();
    if (session != null) {
      synchronized (session) {
        session.removeAttribute(KEY);
      }
    }
  }
}
