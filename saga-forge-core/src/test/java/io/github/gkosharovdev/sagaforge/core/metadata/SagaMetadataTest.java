package io.github.gkosharovdev.sagaforge.core.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rules of {@code specs/019-process-metadata} contracts/metadata-spi.md. */
class SagaMetadataTest {

  @Test
  @DisplayName("of keeps the entries in key order and compares by entries")
  void ofKeepsEntries() {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("tenant", "t-9");
    entries.put("correlationId", "c-1");

    SagaMetadata metadata = SagaMetadata.of(entries);

    assertThat(metadata.asMap())
        .containsExactly(Map.entry("correlationId", "c-1"), Map.entry("tenant", "t-9"));
    assertThat(metadata.get("tenant")).contains("t-9");
    assertThat(metadata.get("other")).isEmpty();
    assertThat(metadata)
        .isEqualTo(SagaMetadata.of(Map.of("tenant", "t-9", "correlationId", "c-1")));
    assertThat(metadata.isEmpty()).isFalse();
    assertThatThrownBy(() -> metadata.asMap().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("empty is empty and of an empty map is empty")
  void empty() {
    assertThat(SagaMetadata.empty().isEmpty()).isTrue();
    assertThat(SagaMetadata.of(Map.of())).isEqualTo(SagaMetadata.empty());
  }

  @Test
  @DisplayName("of rejects a null map, blank and null keys, null values and over-long parts")
  void ofIsStrict() {
    Map<String, String> nullKey = new HashMap<>();
    nullKey.put(null, "v");
    Map<String, String> nullValue = new HashMap<>();
    nullValue.put("k", null);

    assertThatThrownBy(() -> SagaMetadata.of(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SagaMetadata.of(Map.of(" ", "v")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
    assertThatThrownBy(() -> SagaMetadata.of(nullKey)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SagaMetadata.of(nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'k'");
    assertThatThrownBy(() -> SagaMetadata.of(Map.of("k".repeat(129), "v")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("128");
    assertThatThrownBy(() -> SagaMetadata.of(Map.of("k", "v".repeat(1025))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'k'")
        .hasMessageContaining("1024");
    assertThatThrownBy(() -> SagaMetadata.of(entries(33)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("32");
  }

  @Test
  @DisplayName("the bounds themselves are allowed")
  void boundsAllowed() {
    assertThat(SagaMetadata.of(Map.of("k".repeat(128), "v".repeat(1024))).asMap()).hasSize(1);
    assertThat(SagaMetadata.of(entries(32)).asMap()).hasSize(32);
  }

  @Test
  @DisplayName("sanitized drops each offending entry and names its key once, never failing")
  void sanitizedDrops() {
    Map<String, String> entries = new HashMap<>();
    entries.put("ok", "1");
    entries.put(" ", "blank");
    entries.put(null, "null key");
    entries.put("nullValue", null);
    entries.put("long", "v".repeat(1025));
    List<String> dropped = new ArrayList<>();

    SagaMetadata metadata = SagaMetadata.sanitized(entries, dropped::add);

    assertThat(metadata.asMap()).containsExactly(Map.entry("ok", "1"));
    assertThat(dropped).containsExactlyInAnyOrder(" ", "", "nullValue", "long");
  }

  @Test
  @DisplayName("sanitized keeps the first 32 valid entries in key order")
  void sanitizedCaps() {
    List<String> dropped = new ArrayList<>();

    SagaMetadata metadata = SagaMetadata.sanitized(entries(34), dropped::add);

    assertThat(metadata.asMap()).hasSize(32).containsKey("key-00").doesNotContainKey("key-32");
    assertThat(dropped).containsExactly("key-32", "key-33");
  }

  @Test
  @DisplayName("sanitized of null or empty is empty; a null callback is rejected")
  void sanitizedEdges() {
    assertThat(SagaMetadata.sanitized(null, key -> {})).isEqualTo(SagaMetadata.empty());
    assertThat(SagaMetadata.sanitized(Map.of(), key -> {})).isEqualTo(SagaMetadata.empty());
    assertThatThrownBy(() -> SagaMetadata.sanitized(Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, String> entries(int count) {
    Map<String, String> entries = new HashMap<>();
    for (int index = 0; index < count; index++) {
      entries.put(String.format("key-%02d", index), "value");
    }
    return entries;
  }
}
