package io.github.gkosharovdev.sagaforge.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link MetadataAccessorContract} for the test double, plus {@link MetadataAccessor#NONE}. */
class ThreadLocalMetadataAccessorContractTest extends MetadataAccessorContract {

  private final ThreadLocalMetadataAccessor accessor = new ThreadLocalMetadataAccessor();

  @Override
  protected MetadataAccessor accessor() {
    return accessor;
  }

  @Override
  protected void setThreadState(Map<String, String> entries) {
    accessor.set(entries);
  }

  @Override
  protected void clearThreadState() {
    accessor.clear();
  }

  @Test
  @DisplayName("NONE captures nothing and restores nothing")
  void none() {
    MetadataAccessor none = MetadataAccessor.NONE;

    try (MetadataAccessor.Scope scope = none.restore(SagaMetadata.of(Map.of("k", "v")))) {
      assertThat(none.capture()).isEmpty();
    }
    assertThatThrownBy(() -> none.restore(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
