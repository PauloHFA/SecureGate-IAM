package com.securegate.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class IamCoreApplication {
  public static void main(String[] args) {
    if (args.length == 1 && "--migrate-only".equals(args[0])) {
      com.securegate.iam.bootstrap.DatabaseMigration.run(System.getenv());
      return;
    }
    SpringApplication.run(IamCoreApplication.class, args);
  }
}
