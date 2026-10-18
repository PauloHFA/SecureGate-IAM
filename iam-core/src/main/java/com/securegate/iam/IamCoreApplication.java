package com.securegate.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class IamCoreApplication {
  public static void main(String[] args) {
    SpringApplication.run(IamCoreApplication.class, args);
  }
}
