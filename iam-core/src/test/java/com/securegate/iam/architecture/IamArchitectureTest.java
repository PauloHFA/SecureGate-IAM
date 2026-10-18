package com.securegate.iam.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "com.securegate.iam",
    importOptions = ImportOption.DoNotIncludeTests.class)
class IamArchitectureTest {
  @ArchTest static final ArchRule domain = ArchitectureRules.DOMAIN_IS_FRAMEWORK_FREE;
  @ArchTest static final ArchRule application = ArchitectureRules.APPLICATION_DEPENDS_INWARD;
  @ArchTest static final ArchRule presentation = ArchitectureRules.PRESENTATION_USES_APPLICATION;

  @ArchTest
  static final ArchRule infrastructure = ArchitectureRules.INFRASTRUCTURE_DOES_NOT_USE_PRESENTATION;

  @ArchTest static final ArchRule contexts = ArchitectureRules.CONTEXTS_USE_ONLY_PORTS_OR_EVENTS;
}
