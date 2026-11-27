package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaDifference.ObjectKind;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Column;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Constraint;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares an expected {@link SchemaSnapshot} with a found one. Tables and columns are matched by
 * name, constraints by {@link Constraint#key()}, indexes by definition. Anything the found snapshot
 * has beyond the expected one is ignored.
 */
final class SchemaComparison {

  private SchemaComparison() {}

  /**
   * @param expected the shape a version needs
   * @param found the shape read from a database
   * @return every missing or different object, unordered
   */
  static List<SchemaDifference> compare(SchemaSnapshot expected, SchemaSnapshot found) {
    List<SchemaDifference> differences = new ArrayList<>();
    for (Table table : expected.tables().values()) {
      Table actual = found.tables().get(table.name());
      if (actual == null) {
        differences.add(
            SchemaDifference.missing(table.name(), ObjectKind.TABLE, table.name(), "table"));
      } else {
        compareTable(table, actual, differences);
      }
    }
    return differences;
  }

  private static void compareTable(Table expected, Table found, List<SchemaDifference> out) {
    String table = expected.name();
    Map<String, Column> columns = byKey(found.columns(), Column::name);
    for (Column column : expected.columns()) {
      Column actual = columns.get(column.name());
      if (actual == null) {
        out.add(SchemaDifference.missing(table, ObjectKind.COLUMN, column.name(), column.shape()));
      } else if (!actual.shape().equals(column.shape())) {
        out.add(
            SchemaDifference.different(
                table, ObjectKind.COLUMN, column.name(), column.shape(), actual.shape()));
      }
    }
    Map<String, Constraint> constraints = byKey(found.constraints(), Constraint::key);
    for (Constraint constraint : expected.constraints()) {
      Constraint actual = constraints.get(constraint.key());
      if (actual == null) {
        out.add(
            SchemaDifference.missing(
                table, ObjectKind.CONSTRAINT, constraint.key(), constraint.shape()));
      } else if (!actual.shape().equals(constraint.shape())) {
        out.add(
            SchemaDifference.different(
                table,
                ObjectKind.CONSTRAINT,
                constraint.key(),
                constraint.shape(),
                actual.shape()));
      }
    }
    Set<String> indexes = new HashSet<>(found.indexes());
    for (String index : expected.indexes()) {
      if (!indexes.contains(index)) {
        out.add(SchemaDifference.missing(table, ObjectKind.INDEX, index, "index"));
      }
    }
  }

  private static <T> Map<String, T> byKey(List<T> values, Function<T, String> key) {
    return values.stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
  }
}
