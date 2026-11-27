package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Column;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Constraint;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.ConstraintKind;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Table;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the shape of named tables in the connection's {@code current_schema()} from {@code
 * pg_catalog}. Reads only.
 */
final class CatalogReader {

  private static final String TABLES =
      "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p')"
          + " AND c.relname = ANY (?)";

  private static final String COLUMNS =
      "SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull"
          + " FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid"
          + " JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = current_schema() AND c.relname = ANY (?)"
          + " AND a.attnum > 0 AND NOT a.attisdropped ORDER BY c.relname, a.attnum";

  private static final String CONSTRAINTS =
      "SELECT c.relname, con.contype,"
          + " ARRAY(SELECT a.attname FROM unnest(con.conkey) WITH ORDINALITY k(num, ord)"
          + "   JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.num"
          + "   ORDER BY k.ord),"
          + " r.relname,"
          + " ARRAY(SELECT a.attname FROM unnest(con.confkey) WITH ORDINALITY k(num, ord)"
          + "   JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.num"
          + "   ORDER BY k.ord),"
          + " con.confdeltype, pg_get_constraintdef(con.oid)"
          + " FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid"
          + " JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " LEFT JOIN pg_class r ON r.oid = con.confrelid"
          + " WHERE n.nspname = current_schema() AND c.relname = ANY (?)"
          + " AND con.contype IN ('p', 'u', 'f', 'c')";

  private static final String INDEXES =
      "SELECT c.relname, pg_get_indexdef(i.indexrelid)"
          + " FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid"
          + " JOIN pg_namespace n ON n.oid = c.relnamespace"
          + " WHERE n.nspname = current_schema() AND c.relname = ANY (?)"
          + " AND NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = i.indexrelid"
          + "   AND con.contype IN ('p', 'u', 'x'))";

  private CatalogReader() {}

  /**
   * @param connection an open connection; its {@code current_schema()} is read
   * @param tableNames the tables to read; absent ones are left out of the result
   * @return the shape of the tables that exist
   * @throws SQLException if the catalog cannot be read
   */
  static SchemaSnapshot read(Connection connection, Collection<String> tableNames)
      throws SQLException {
    Array names = connection.createArrayOf("text", tableNames.toArray());
    Map<String, TableBuilder> tables = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(TABLES)) {
      statement.setArray(1, names);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          tables.put(rows.getString(1), new TableBuilder(rows.getString(1)));
        }
      }
    }
    try (PreparedStatement statement = connection.prepareStatement(COLUMNS)) {
      statement.setArray(1, names);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          tables
              .get(rows.getString(1))
              .columns
              .add(new Column(rows.getString(2), rows.getString(3), rows.getBoolean(4)));
        }
      }
    }
    try (PreparedStatement statement = connection.prepareStatement(CONSTRAINTS)) {
      statement.setArray(1, names);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          tables.get(rows.getString(1)).constraints.add(constraint(rows));
        }
      }
    }
    try (PreparedStatement statement = connection.prepareStatement(INDEXES)) {
      statement.setArray(1, names);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          tables.get(rows.getString(1)).indexes.add(Definitions.index(rows.getString(2)));
        }
      }
    }
    Map<String, Table> result = new LinkedHashMap<>();
    tables.forEach((name, builder) -> result.put(name, builder.build()));
    return new SchemaSnapshot(result);
  }

  private static Constraint constraint(ResultSet row) throws SQLException {
    String type = row.getString(2);
    List<String> columns = strings(row.getArray(3));
    return switch (type) {
      case "p" -> new Constraint(ConstraintKind.PRIMARY_KEY, columns, null, List.of(), null, null);
      case "u" -> new Constraint(ConstraintKind.UNIQUE, columns, null, List.of(), null, null);
      case "f" ->
          new Constraint(
              ConstraintKind.FOREIGN_KEY,
              columns,
              row.getString(4),
              strings(row.getArray(5)),
              onDelete(row.getString(6)),
              null);
      case "c" ->
          new Constraint(
              ConstraintKind.CHECK,
              List.of(),
              null,
              List.of(),
              null,
              Definitions.check(row.getString(7)));
      default -> throw new IllegalStateException("unexpected constraint type " + type);
    };
  }

  private static String onDelete(String code) {
    return switch (code) {
      case "a" -> "NO ACTION";
      case "r" -> "RESTRICT";
      case "c" -> "CASCADE";
      case "n" -> "SET NULL";
      case "d" -> "SET DEFAULT";
      default -> throw new IllegalStateException("unexpected on-delete action " + code);
    };
  }

  private static List<String> strings(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
  }

  private static final class TableBuilder {
    private final String name;
    private final List<Column> columns = new ArrayList<>();
    private final List<Constraint> constraints = new ArrayList<>();
    private final List<String> indexes = new ArrayList<>();

    private TableBuilder(String name) {
      this.name = name;
    }

    private Table build() {
      return new Table(name, columns, constraints, indexes);
    }
  }
}
