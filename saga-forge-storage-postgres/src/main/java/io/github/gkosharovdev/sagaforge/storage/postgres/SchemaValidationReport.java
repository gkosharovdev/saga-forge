package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.Severity;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The result of checking a database against one {@link SchemaVersion}: every difference, failures
 * first, then warnings, each ordered by table, kind and name.
 *
 * @param expected the version checked against
 * @param differences every difference found; copied and ordered
 */
public record SchemaValidationReport(SchemaVersion expected, List<SchemaDifference> differences) {

  private static final Comparator<SchemaDifference> ORDER =
      Comparator.comparing(SchemaDifference::severity)
          .thenComparing(SchemaDifference::table)
          .thenComparing(SchemaDifference::kind)
          .thenComparing(SchemaDifference::name);

  /** Validates and orders the components. */
  public SchemaValidationReport {
    Objects.requireNonNull(expected, "expected");
    differences = differences.stream().sorted(ORDER).toList();
  }

  /**
   * @return {@code true} when no difference fails the check; warnings are allowed
   */
  public boolean passed() {
    return failures().isEmpty();
  }

  /**
   * @return the differences that fail the check
   */
  public List<SchemaDifference> failures() {
    return differences.stream().filter(d -> d.severity() == Severity.FAILURE).toList();
  }

  /**
   * @return the differences reported as warnings (missing or different indexes)
   */
  public List<SchemaDifference> warnings() {
    return differences.stream().filter(d -> d.severity() == Severity.WARNING).toList();
  }

  /**
   * @return a first line naming the version and the counts, then one indented line per difference
   */
  public String describe() {
    int failures = failures().size();
    int warnings = warnings().size();
    String head =
        (passed()
                ? "Saga Forge's schema matches " + expected
                : "Saga Forge's schema does not match " + expected)
            + ": "
            + count(failures, "failure")
            + ", "
            + count(warnings, "warning")
            + ".";
    if (differences.isEmpty()) {
      return head;
    }
    return head
        + differences.stream().map(d -> "\n  " + d.describe()).collect(Collectors.joining());
  }

  private static String count(int n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }
}
