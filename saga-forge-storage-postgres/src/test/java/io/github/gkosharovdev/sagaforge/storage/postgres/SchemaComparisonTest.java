package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.ObjectKind;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.Problem;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.Severity;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Column;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Constraint;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.ConstraintKind;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Matching by meaning, per specs/018 contracts/schema-check.md "What is compared". */
class SchemaComparisonTest {

  private static final Constraint PK =
      new Constraint(ConstraintKind.PRIMARY_KEY, List.of("id"), null, List.of(), null, null);
  private static final Constraint UNIQUE =
      new Constraint(ConstraintKind.UNIQUE, List.of("a", "b"), null, List.of(), null, null);
  private static final Constraint FK =
      new Constraint(
          ConstraintKind.FOREIGN_KEY, List.of("parent"), "p", List.of("id"), "CASCADE", null);
  private static final Constraint CHECK =
      new Constraint(ConstraintKind.CHECK, List.of(), null, List.of(), null, "CHECK ((id > 0))");
  private static final String INDEX = "USING btree (a)";

  private static Table expectedTable() {
    return new Table(
        "t",
        List.of(new Column("id", "text", true), new Column("a", "integer", false)),
        List.of(PK, UNIQUE, FK, CHECK),
        List.of(INDEX));
  }

  private static SchemaSnapshot snapshot(Table... tables) {
    Map<String, Table> map = new java.util.LinkedHashMap<>();
    for (Table table : tables) {
      map.put(table.name(), table);
    }
    return new SchemaSnapshot(map);
  }

  private static List<SchemaDifference> compareWith(UnaryOperator<Table> change) {
    return SchemaComparison.compare(
        snapshot(expectedTable()), snapshot(change.apply(expectedTable())));
  }

  private static Table with(
      Table table, List<Column> columns, List<Constraint> constraints, List<String> indexes) {
    return new Table(table.name(), columns, constraints, indexes);
  }

  @Test
  @DisplayName("equal shapes give no difference")
  void equal() {
    assertThat(compareWith(t -> t)).isEmpty();
  }

  @Test
  @DisplayName("a missing table is one failure")
  void missingTable() {
    assertThat(SchemaComparison.compare(snapshot(expectedTable()), snapshot()))
        .containsExactly(SchemaDifference.missing("t", ObjectKind.TABLE, "t", "table"));
  }

  @Test
  @DisplayName("a missing column, a wrong type and a wrong nullability fail")
  void columns() {
    assertThat(
            compareWith(
                t ->
                    with(
                        t,
                        List.of(new Column("id", "integer", true)),
                        t.constraints(),
                        t.indexes())))
        .containsExactlyInAnyOrder(
            SchemaDifference.different(
                "t", ObjectKind.COLUMN, "id", "text NOT NULL", "integer NOT NULL"),
            SchemaDifference.missing("t", ObjectKind.COLUMN, "a", "integer NULL"));
    assertThat(
            compareWith(
                t ->
                    with(
                        t,
                        List.of(new Column("id", "text", false), new Column("a", "integer", false)),
                        t.constraints(),
                        t.indexes())))
        .containsExactly(
            SchemaDifference.different("t", ObjectKind.COLUMN, "id", "text NOT NULL", "text NULL"));
  }

  @Test
  @DisplayName("missing primary key, unique, check and a foreign key with another on-delete fail")
  void constraints() {
    Constraint restrictFk =
        new Constraint(
            ConstraintKind.FOREIGN_KEY, List.of("parent"), "p", List.of("id"), "RESTRICT", null);
    List<SchemaDifference> differences =
        compareWith(t -> with(t, t.columns(), List.of(restrictFk), t.indexes()));
    assertThat(differences)
        .containsExactlyInAnyOrder(
            SchemaDifference.missing("t", ObjectKind.CONSTRAINT, PK.key(), PK.shape()),
            SchemaDifference.missing("t", ObjectKind.CONSTRAINT, UNIQUE.key(), UNIQUE.shape()),
            SchemaDifference.missing("t", ObjectKind.CONSTRAINT, CHECK.key(), CHECK.shape()),
            SchemaDifference.different(
                "t", ObjectKind.CONSTRAINT, FK.key(), "ON DELETE CASCADE", "ON DELETE RESTRICT"));
    assertThat(differences).allMatch(d -> d.severity() == Severity.FAILURE);
  }

  @Test
  @DisplayName("a changed check definition is a missing check; a missing index is a warning")
  void checkAndIndex() {
    Constraint otherCheck =
        new Constraint(ConstraintKind.CHECK, List.of(), null, List.of(), null, "CHECK ((id > 1))");
    List<SchemaDifference> differences =
        compareWith(t -> with(t, t.columns(), List.of(PK, UNIQUE, FK, otherCheck), List.of()));
    assertThat(differences)
        .extracting(SchemaDifference::kind, SchemaDifference::problem, SchemaDifference::severity)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(
                ObjectKind.CONSTRAINT, Problem.MISSING, Severity.FAILURE),
            org.assertj.core.groups.Tuple.tuple(
                ObjectKind.INDEX, Problem.MISSING, Severity.WARNING));
  }

  @Test
  @DisplayName("extra tables, columns, constraints and indexes are ignored")
  void extrasIgnored() {
    Table expected = expectedTable();
    List<Column> columns = new ArrayList<>(expected.columns());
    columns.add(new Column("app_note", "text", false));
    List<Constraint> constraints = new ArrayList<>(expected.constraints());
    constraints.add(
        new Constraint(ConstraintKind.CHECK, List.of(), null, List.of(), null, "CHECK (true)"));
    Table found = with(expected, columns, constraints, List.of(INDEX, "USING btree (app_note)"));
    Table extraTable =
        new Table("app_table", List.of(new Column("x", "text", false)), List.of(), List.of());
    assertThat(SchemaComparison.compare(snapshot(expected), snapshot(found, extraTable))).isEmpty();
  }
}
