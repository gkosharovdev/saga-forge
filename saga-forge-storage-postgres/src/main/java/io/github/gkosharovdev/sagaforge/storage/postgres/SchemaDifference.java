package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.util.Objects;

/**
 * One way a database differs from the schema a {@link SchemaVersion} needs. Extra tables, columns,
 * constraints and indexes are never differences.
 *
 * @param table the table
 * @param kind what kind of object differs
 * @param name the column name; for a constraint or an index its definition (they are matched by
 *     meaning, not by name); for a table the table name
 * @param problem whether the object is missing or has another shape
 * @param expected the shape the version needs, for example {@code "text NOT NULL"}
 * @param found the shape found, or {@code null} when {@link Problem#MISSING}
 * @param severity {@link Severity#WARNING} for an index, else {@link Severity#FAILURE}; derived
 *     from the kind
 */
public record SchemaDifference(
    String table,
    ObjectKind kind,
    String name,
    Problem problem,
    String expected,
    String found,
    Severity severity) {

  /** The kind of a schema object. */
  public enum ObjectKind {
    TABLE,
    COLUMN,
    CONSTRAINT,
    INDEX
  }

  /** How an object differs. */
  public enum Problem {
    MISSING,
    DIFFERENT
  }

  /** Whether a difference fails the check. */
  public enum Severity {
    FAILURE,
    WARNING
  }

  /** Validates the components. */
  public SchemaDifference {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(problem, "problem");
    Objects.requireNonNull(expected, "expected");
    if ((problem == Problem.MISSING) != (found == null)) {
      throw new IllegalArgumentException("found is null exactly when the object is missing");
    }
    if (severity != severityOf(kind)) {
      throw new IllegalArgumentException(kind + " differences have severity " + severityOf(kind));
    }
  }

  /**
   * @param table the table
   * @param kind the object's kind
   * @param name the column name, the constraint or index definition, or the table name
   * @param expected the shape the version needs
   * @return a difference for an object that does not exist
   */
  public static SchemaDifference missing(
      String table, ObjectKind kind, String name, String expected) {
    return new SchemaDifference(
        table, kind, name, Problem.MISSING, expected, null, severityOf(kind));
  }

  /**
   * @param table the table
   * @param kind the object's kind
   * @param name the column name, the constraint or index definition, or the table name
   * @param expected the shape the version needs
   * @param found the shape found
   * @return a difference for an object whose shape differs
   */
  public static SchemaDifference different(
      String table, ObjectKind kind, String name, String expected, String found) {
    return new SchemaDifference(
        table, kind, name, Problem.DIFFERENT, expected, found, severityOf(kind));
  }

  private static Severity severityOf(ObjectKind kind) {
    return kind == ObjectKind.INDEX ? Severity.WARNING : Severity.FAILURE;
  }

  /**
   * @return one line, for example {@code "FAILURE saga_instance: column resolution is missing
   *     (expected jsonb NULL)"}
   */
  public String describe() {
    String object =
        switch (kind) {
          case TABLE -> "table";
          case COLUMN -> "column " + name;
          case CONSTRAINT -> "constraint " + name;
          case INDEX -> "index " + name;
        };
    String what =
        problem == Problem.MISSING
            ? " is missing (expected " + expected + ")"
            : " is different (expected " + expected + ", found " + found + ")";
    return severity + " " + table + ": " + object + what;
  }
}
