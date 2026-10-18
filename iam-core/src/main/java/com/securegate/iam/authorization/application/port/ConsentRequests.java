package com.securegate.iam.authorization.application.port;

import java.util.List;
import java.util.Optional;

public interface ConsentRequests {
  String SESSION_ID = "securegate.sid";
  String EXPIRES = "securegate.consent.expires";

  Optional<ConsentView> find(String state, String clientId, String userId, String sid);

  record ConsentView(String state, String clientId, String name, List<String> scopes) {
    public ConsentView {
      scopes = List.copyOf(scopes);
    }
  }
}
