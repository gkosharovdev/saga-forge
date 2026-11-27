package io.github.gkosharovdev.sagaforge.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract every {@link MetadataAccessor} keeps ({@code specs/019-process-metadata}
 * contracts/metadata-spi.md). A subclass supplies the accessor and a way to set and read the
 * thread's state outside the accessor, using the keys {@code "correlationId"} and {@code "tenant"},
 * both of which the accessor must handle.
 */
public abstract class MetadataAccessorContract {

  /**
   * @return the accessor under test, handling the keys {@code correlationId} and {@code tenant}
   */
  protected abstract MetadataAccessor accessor();

  /**
   * Sets the thread's state as the host would (a request filter, say).
   *
   * @param entries the entries to set; keys outside the two above are not used
   */
  protected abstract void setThreadState(Map<String, String> entries);

  /** Clears the thread's state. */
  protected abstract void clearThreadState();

  @AfterEach
  void clear() {
    clearThreadState();
  }

  @Test
  @DisplayName("capture returns what the host put on the thread")
  void captureReadsThreadState() {
    setThreadState(Map.of("correlationId", "c-1"));

    assertThat(accessor().capture()).containsExactly(Map.entry("correlationId", "c-1"));
  }

  @Test
  @DisplayName("capture on an empty thread is empty, never null")
  void captureEmpty() {
    assertThat(accessor().capture()).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("restore makes the metadata current; close puts back the previous state")
  void restoreAndClose() {
    setThreadState(Map.of("correlationId", "before"));

    try (MetadataAccessor.Scope scope =
        accessor().restore(SagaMetadata.of(Map.of("correlationId", "c-1", "tenant", "t-9")))) {
      assertThat(accessor().capture())
          .containsEntry("correlationId", "c-1")
          .containsEntry("tenant", "t-9");
    }

    assertThat(accessor().capture()).containsExactly(Map.entry("correlationId", "before"));
  }

  @Test
  @DisplayName("close removes keys the thread did not hold before")
  void closeRemovesNewKeys() {
    try (MetadataAccessor.Scope scope =
        accessor().restore(SagaMetadata.of(Map.of("tenant", "t-9")))) {
      assertThat(accessor().capture()).containsEntry("tenant", "t-9");
    }

    assertThat(accessor().capture()).isEmpty();
  }

  @Test
  @DisplayName("nested scopes unwind in order")
  void nested() {
    MetadataAccessor accessor = accessor();
    try (MetadataAccessor.Scope outer =
        accessor.restore(SagaMetadata.of(Map.of("correlationId", "outer")))) {
      try (MetadataAccessor.Scope inner =
          accessor.restore(SagaMetadata.of(Map.of("correlationId", "inner")))) {
        assertThat(accessor.capture()).containsEntry("correlationId", "inner");
      }
      assertThat(accessor.capture()).containsEntry("correlationId", "outer");
    }
    assertThat(accessor.capture()).isEmpty();
  }

  @Test
  @DisplayName("restore rejects null")
  void restoreNull() {
    assertThatThrownBy(() -> accessor().restore(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
