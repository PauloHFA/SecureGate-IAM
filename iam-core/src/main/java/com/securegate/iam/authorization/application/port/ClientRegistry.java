package com.securegate.iam.authorization.application.port;

import com.securegate.iam.authorization.application.ClientView;
import com.securegate.iam.authorization.domain.BrowserClient;
import com.securegate.iam.authorization.domain.MachineClient;
import java.util.Optional;

public interface ClientRegistry {
  ClientView create(MachineClient definition, String secretHash);

  ClientView createBrowser(BrowserClient definition, String secretHash);

  Optional<ClientView> find(String clientId);
}
