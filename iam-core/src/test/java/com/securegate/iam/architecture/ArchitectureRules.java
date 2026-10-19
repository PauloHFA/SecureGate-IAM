package com.securegate.iam.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

final class ArchitectureRules {
  private static final String BASE = "com.securegate.iam.";
  private static final Set<String> CONTEXTS =
      Set.of("identity", "access", "policy", "authorization", "audit", "session");

  static final ArchRule DOMAIN_IS_FRAMEWORK_FREE =
      classes()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(
              "java..", "javax.crypto..", "com.securegate.iam..domain..", "com.securegate.events..")
          .allowEmptyShould(true);

  static final ArchRule APPLICATION_DEPENDS_INWARD =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..infrastructure..", "..presentation..", "..bootstrap..")
          .allowEmptyShould(true);

  static final ArchRule PRESENTATION_USES_APPLICATION =
      noClasses()
          .that()
          .resideInAPackage("..presentation..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..domain..", "..infrastructure..", "..bootstrap..")
          .allowEmptyShould(true);

  static final ArchRule INFRASTRUCTURE_DOES_NOT_USE_PRESENTATION =
      noClasses()
          .that()
          .resideInAPackage("..infrastructure..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..presentation..", "..bootstrap..")
          .allowEmptyShould(true);

  static final ArchRule CONTEXTS_USE_ONLY_PORTS_OR_EVENTS =
      classes()
          .should(
              new ArchCondition<>("cross context boundaries only via ports or domain events") {
                @Override
                public void check(JavaClass source, ConditionEvents events) {
                  String sourceContext = contextOf(source);
                  if (sourceContext == null) {
                    return;
                  }
                  source
                      .getDirectDependenciesFromSelf()
                      .forEach(
                          dependency -> {
                            JavaClass target = dependency.getTargetClass();
                            String targetContext = contextOf(target);
                            if (targetContext == null || targetContext.equals(sourceContext)) {
                              return;
                            }
                            String packageName = target.getPackageName();
                            boolean port =
                                belongsTo(packageName, BASE + targetContext + ".application.port");
                            boolean event =
                                belongsTo(packageName, BASE + targetContext + ".domain.event");
                            if (!port && !event) {
                              events.add(
                                  SimpleConditionEvent.violated(
                                      source, dependency.getDescription()));
                            }
                          });
                }
              });

  private static boolean belongsTo(String packageName, String boundary) {
    return packageName.equals(boundary) || packageName.startsWith(boundary + ".");
  }

  private static String contextOf(JavaClass type) {
    String packageName = type.getPackageName();
    if (!packageName.startsWith(BASE)) {
      return null;
    }
    String context = packageName.substring(BASE.length()).split("\\.")[0];
    return CONTEXTS.contains(context) ? context : null;
  }

  private ArchitectureRules() {}
}
