package com.securegate.iam.audit.application.port;

import com.securegate.iam.audit.application.AuditEventView;
import com.securegate.iam.audit.application.AuditQuery;
import java.util.List;

public interface AuditEventQueries {
  /** Returns at most limit+1 rows in (occurred_at DESC, id DESC) order. */
  List<AuditEventView> find(AuditQuery query);
}
