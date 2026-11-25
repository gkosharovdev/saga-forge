package io.github.gkosharovdev.sagaforge.runtime.internal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Rule U1 of specs/011-saga-outcome-events contracts/unit-of-work.md: only {@link UnitOfWork}
 * begins a transaction, locks or writes a saga instance, or hands a batch to the outbox, so every
 * committed unit of work takes one path to the outbox.
 */
class UnitOfWorkBoundaryTest {

  private static final Map<Class<?>, Set<String>> BOUNDARY =
      Map.of(
          TransactionBoundary.class, Set.of("begin"),
          StorageProvider.class, Set.of("findByIdAndLock", "save"),
          Outbox.class, Set.of("store", "committed"));

  private static final JavaClasses RUNTIME =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.gkosharovdev.sagaforge.runtime");

  private static final DescribedPredicate<JavaAccess<?>> BOUNDARY_CALL =
      DescribedPredicate.describe(
          "TransactionBoundary.begin, StorageProvider.findByIdAndLock/save or Outbox.store/committed",
          access -> crosses(access.getTarget()));

  private static boolean crosses(AccessTarget target) {
    return BOUNDARY.entrySet().stream()
        .anyMatch(
            entry ->
                target.getOwner().isAssignableTo(entry.getKey())
                    && entry.getValue().contains(target.getName()));
  }

  @Test
  void onlyTheUnitOfWorkCrossesTheTransactionBoundary() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(UnitOfWork.class.getName())
        .should()
        .accessTargetWhere(BOUNDARY_CALL)
        .because("the unit of work is the only owner of the runtime's transactions (011 rule U1)")
        .check(RUNTIME);
  }

  @Test
  void theUnitOfWorkDoesCrossIt() {
    Set<String> crossed = new java.util.TreeSet<>();
    RUNTIME.get(UnitOfWork.class).getAccessesFromSelf().stream()
        .map(JavaAccess::getTarget)
        .filter(UnitOfWorkBoundaryTest::crosses)
        .forEach(target -> crossed.add(target.getName()));

    assertThat(crossed)
        .containsExactlyInAnyOrder("begin", "findByIdAndLock", "save", "store", "committed");
  }
}
