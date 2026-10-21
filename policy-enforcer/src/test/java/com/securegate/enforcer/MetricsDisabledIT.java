package com.securegate.enforcer;

import static org.junit.jupiter.api.Assertions.*;

import com.securegate.enforcer.infrastructure.MetricsHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class MetricsDisabledIT {
  @Autowired ApplicationContext context;

  @Test
  void defaultApplicationHasNoListenerOrWebContext() {
    assertTrue(context.getBeansOfType(MetricsHttpServer.class).isEmpty());
    assertFalse(
        context instanceof org.springframework.boot.web.context.WebServerApplicationContext);
  }
}
