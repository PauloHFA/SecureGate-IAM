package com.securegate.iam.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securegate.iam.access.application.port.AccessLookupPort;
import com.securegate.iam.access.infrastructure.persistence.ForeignRepository;
import com.securegate.iam.identity.application.CrossContextRepositoryAccess;
import com.securegate.iam.identity.application.ValidCrossContextPort;
import com.securegate.iam.identity.domain.FrameworkCoupledDomain;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {
  @Test
  void detectsSpringInTheDomain() {
    var classes = new ClassFileImporter().importClasses(FrameworkCoupledDomain.class);
    var result = ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE.evaluate(classes);
    assertTrue(result.hasViolation());
    assertTrue(result.getFailureReport().toString().contains("org.springframework"));
  }

  @Test
  void detectsAnApplicationDependingOnInfrastructure() {
    var classes =
        new ClassFileImporter()
            .importClasses(CrossContextRepositoryAccess.class, ForeignRepository.class);
    assertTrue(ArchitectureRules.APPLICATION_DEPENDS_INWARD.evaluate(classes).hasViolation());
  }

  @Test
  void detectsDirectAccessToAnotherContextsRepository() {
    var classes =
        new ClassFileImporter()
            .importClasses(CrossContextRepositoryAccess.class, ForeignRepository.class);
    var result = ArchitectureRules.CONTEXTS_USE_ONLY_PORTS_OR_EVENTS.evaluate(classes);
    assertTrue(result.hasViolation());
    assertTrue(result.getFailureReport().toString().contains("ForeignRepository"));
  }

  @Test
  void permitsCollaborationThroughAnotherContextsPort() {
    var classes =
        new ClassFileImporter().importClasses(ValidCrossContextPort.class, AccessLookupPort.class);
    assertFalse(
        ArchitectureRules.CONTEXTS_USE_ONLY_PORTS_OR_EVENTS.evaluate(classes).hasViolation());
    assertFalse(ArchitectureRules.APPLICATION_DEPENDS_INWARD.evaluate(classes).hasViolation());
  }
}
