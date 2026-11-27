package io.github.gkosharovdev.sagaforge.spring;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Domain markers are read through {@code DomainTypes} only (specs/016-marker-spi-jmolecules FR-003,
 * SC-005): no main class of this module refers to Saga Forge's domain annotations, so a vocabulary
 * added through {@code DomainTypeResolver} is honoured everywhere.
 */
class DomainAnnotationAccessTest {

  private static final String MODULE_PACKAGE = "io.github.gkosharovdev.sagaforge.spring";

  /** Top-level classes still reading the annotations directly, until they ask DomainTypes. */
  private static final Set<String> NOT_YET_MIGRATED = Set.of();

  private static final JavaClasses MAIN =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(MODULE_PACKAGE);

  private static final DescribedPredicate<JavaClass> MIGRATED =
      DescribedPredicate.describe(
          "migrated to DomainTypes",
          type ->
              !NOT_YET_MIGRATED.contains(
                  topLevelName(type).substring(MODULE_PACKAGE.length() + 1)));

  @Test
  void noMainClassReadsTheDomainAnnotations() {
    noClasses()
        .that(MIGRATED)
        .should()
        .dependOnClassesThat()
        .belongToAnyOf(
            Aggregate.class,
            AggregateIdentifier.class,
            DomainEvent.class,
            Command.class,
            CommandHandler.class)
        .because("domain markers are answered by DomainTypes, whatever the vocabulary")
        .check(MAIN);
  }

  private static String topLevelName(JavaClass type) {
    String name = type.getName();
    int nested = name.indexOf('$');
    return nested < 0 ? name : name.substring(0, nested);
  }
}
