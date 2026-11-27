package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Column;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Constraint;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.ConstraintKind;
import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaSnapshot.Table;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SchemaSnapshotJsonTest {

  @Test
  @DisplayName("a snapshot survives toJson and fromJson unchanged")
  void roundTrip() {
    SchemaSnapshot snapshot =
        new SchemaSnapshot(
            Map.of(
                "child",
                new Table(
                    "child",
                    List.of(new Column("id", "text", true), new Column("parent", "text", false)),
                    List.of(
                        new Constraint(
                            ConstraintKind.PRIMARY_KEY, List.of("id"), null, List.of(), null, null),
                        new Constraint(
                            ConstraintKind.FOREIGN_KEY,
                            List.of("parent"),
                            "parent_table",
                            List.of("id"),
                            "CASCADE",
                            null),
                        new Constraint(
                            ConstraintKind.CHECK,
                            List.of(),
                            null,
                            List.of(),
                            null,
                            "CHECK ((id <> ''::text))")),
                    List.of("USING btree (parent)"))));

    String json = snapshot.toJson();

    assertThat(SchemaSnapshot.fromJson(JsonMapper.builder().build().readTree(json)))
        .isEqualTo(snapshot);
    assertThat(SchemaSnapshot.fromJson(JsonMapper.builder().build().readTree(json)).toJson())
        .isEqualTo(json);
  }

  @Test
  @DisplayName("every shipped version has a snapshot holding saga_instance")
  void shippedSnapshotsLoad() {
    for (SchemaVersion version : PostgresSchema.versions()) {
      assertThat(SchemaSnapshot.load(version).tables())
          .as(version.toString())
          .containsKey("saga_instance");
    }
  }
}
