package com.securegate.enforcer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class EnforcerArchitectureTest {
  @Test
  void domainRemainsIndependentOfInfrastructureAndFrameworks() {
    classes()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", "com.securegate.enforcer.domain..", "com.securegate.events..")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.securegate.enforcer"));
  }

  @Test
  void applicationDependsOnDomainAndPorts() {
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infrastructure..", "..bootstrap..")
        .check(
            new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.securegate.enforcer"));
  }
}
