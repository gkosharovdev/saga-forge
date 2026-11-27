package io.github.gkosharovdev.sagaforge.example.jmolecules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The domain references only the JDK, jMolecules and itself: no Saga Forge, Spring or Spring Data
 * type (specs/016-marker-spi-jmolecules SC-002).
 */
class DomainDependsOnlyOnJMoleculesTest {

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.gkosharovdev.sagaforge.example.jmolecules");

  @Test
  @DisplayName("domain classes depend only on java.., org.jmolecules.. and the domain")
  void domainIsSagaForgeFree() {
    classes()
        .that()
        .resideInAPackage("..jmolecules.domain..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "java..",
            "org.jmolecules..",
            "io.github.gkosharovdev.sagaforge.example.jmolecules.domain..")
        .check(MAIN);
  }
}
