package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shape of Saga Forge's tables: columns, constraints and indexes, without names for constraints
 * and indexes (they are matched by meaning). Read from a database by {@link CatalogReader}, or
 * loaded from the snapshot {@code schema-levels/V<N>.json} that this jar ships for each version.
 *
 * @param tables the tables by name
 */
record SchemaSnapshot(Map<String, Table> tables) {

  private static final String RESOURCE_PREFIX =
      "/io/github/gkosharovdev/sagaforge/storage/postgres/schema-levels/V";

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  SchemaSnapshot {
    tables = Map.copyOf(tables);
  }

  /**
   * A table's shape.
   *
   * @param name the table name
   * @param columns the columns in table order
   * @param constraints primary key, unique, foreign key and check constraints
   * @param indexes indexes that back no constraint, as normalised definitions
   */
  record Table(
      String name, List<Column> columns, List<Constraint> constraints, List<String> indexes) {

    Table {
      Objects.requireNonNull(name, "name");
      columns = List.copyOf(columns);
      constraints = constraints.stream().sorted(Comparator.comparing(Constraint::key)).toList();
      indexes = indexes.stream().sorted().toList();
    }
  }

  /**
   * A column's shape.
   *
   * @param name the column name
   * @param type the type as {@code format_type} prints it
   * @param notNull whether the column is {@code NOT NULL}
   */
  record Column(String name, String type, boolean notNull) {

    String shape() {
      return type + (notNull ? " NOT NULL" : " NULL");
    }
  }

  /** The kinds of constraints compared. */
  enum ConstraintKind {
    PRIMARY_KEY,
    UNIQUE,
    FOREIGN_KEY,
    CHECK
  }

  /**
   * A constraint's shape.
   *
   * @param kind the kind
   * @param columns the constrained columns in key order; empty for a check
   * @param references the referenced table of a foreign key, else {@code null}
   * @param referencedColumns the referenced columns of a foreign key, else empty
   * @param onDelete the on-delete action of a foreign key, else {@code null}
   * @param definition the normalised definition of a check, else {@code null}
   */
  record Constraint(
      ConstraintKind kind,
      List<String> columns,
      String references,
      List<String> referencedColumns,
      String onDelete,
      String definition) {

    Constraint {
      Objects.requireNonNull(kind, "kind");
      columns = List.copyOf(columns);
      referencedColumns = List.copyOf(referencedColumns);
    }

    /**
     * @return what identifies the constraint: its kind and columns, with the referenced table and
     *     columns of a foreign key, or the definition of a check
     */
    String key() {
      return switch (kind) {
        case PRIMARY_KEY -> "PRIMARY KEY (" + String.join(", ", columns) + ")";
        case UNIQUE -> "UNIQUE (" + String.join(", ", columns) + ")";
        case FOREIGN_KEY ->
            "FOREIGN KEY ("
                + String.join(", ", columns)
                + ") REFERENCES "
                + references
                + " ("
                + String.join(", ", referencedColumns)
                + ")";
        case CHECK -> definition;
      };
    }

    /**
     * @return the part compared once the key matches: the on-delete action of a foreign key
     */
    String shape() {
      return kind == ConstraintKind.FOREIGN_KEY ? "ON DELETE " + onDelete : key();
    }
  }

  /**
   * @param version a version this jar ships
   * @return the shipped snapshot of that version
   */
  static SchemaSnapshot load(SchemaVersion version) {
    String resource = RESOURCE_PREFIX + version.number() + ".json";
    try (InputStream in = SchemaSnapshot.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(resource + " is not on the class path");
      }
      return fromJson(MAPPER.readTree(in));
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + resource, e);
    }
  }

  /**
   * @return stable JSON: tables, constraints and indexes sorted, columns in table order
   */
  String toJson() {
    ObjectNode root = MAPPER.createObjectNode();
    ArrayNode tableNodes = root.putArray("tables");
    for (Table table : new TreeMap<>(tables).values()) {
      ObjectNode node = tableNodes.addObject();
      node.put("name", table.name());
      ArrayNode columns = node.putArray("columns");
      for (Column column : table.columns()) {
        columns
            .addObject()
            .put("name", column.name())
            .put("type", column.type())
            .put("notNull", column.notNull());
      }
      ArrayNode constraints = node.putArray("constraints");
      for (Constraint constraint : table.constraints()) {
        ObjectNode c = constraints.addObject();
        c.put("kind", constraint.kind().name());
        if (!constraint.columns().isEmpty()) {
          c.set("columns", strings(constraint.columns()));
        }
        if (constraint.references() != null) {
          c.put("references", constraint.references());
          c.set("referencedColumns", strings(constraint.referencedColumns()));
          c.put("onDelete", constraint.onDelete());
        }
        if (constraint.definition() != null) {
          c.put("definition", constraint.definition());
        }
      }
      node.set("indexes", strings(table.indexes()));
    }
    return MAPPER.writeValueAsString(root) + "\n";
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode array = MAPPER.createArrayNode();
    values.forEach(array::add);
    return array;
  }

  static SchemaSnapshot fromJson(JsonNode root) {
    Map<String, Table> tables = new TreeMap<>();
    for (JsonNode node : root.get("tables")) {
      List<Column> columns = new ArrayList<>();
      for (JsonNode c : node.get("columns")) {
        columns.add(
            new Column(
                c.get("name").asString(), c.get("type").asString(), c.get("notNull").asBoolean()));
      }
      List<Constraint> constraints = new ArrayList<>();
      for (JsonNode c : node.get("constraints")) {
        constraints.add(
            new Constraint(
                ConstraintKind.valueOf(c.get("kind").asString()),
                list(c.get("columns")),
                text(c.get("references")),
                list(c.get("referencedColumns")),
                text(c.get("onDelete")),
                text(c.get("definition"))));
      }
      String name = node.get("name").asString();
      tables.put(name, new Table(name, columns, constraints, list(node.get("indexes"))));
    }
    return new SchemaSnapshot(tables);
  }

  private static List<String> list(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node != null) {
      node.forEach(value -> values.add(value.asString()));
    }
    return values;
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() ? null : node.asString();
  }
}
