package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaVersionTest {

  @Test
  @DisplayName("the shipped versions are V1 to V6, oldest first")
  void shippedVersions() {
    assertThat(PostgresSchema.versions())
        .extracting(SchemaVersion::number)
        .containsExactly(1, 2, 3, 4, 5, 6);
    assertThat(PostgresSchema.currentVersion()).hasToString("V6 (process metadata)");
    assertThat(SchemaVersion.of(2)).isEqualTo(new SchemaVersion(2, "saga outcome events"));
    assertThat(SchemaVersion.of(1)).isLessThan(SchemaVersion.of(4));
  }

  @Test
  @DisplayName("an unknown number names the known range")
  void unknownVersion() {
    assertThatThrownBy(() -> SchemaVersion.of(9))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Saga Forge ships no schema version 9; the known versions are 1 to 6");
    assertThatThrownBy(() -> new SchemaVersion(0, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SchemaVersion(1, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
