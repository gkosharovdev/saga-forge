package io.github.gkosharovdev.sagaforge.spring.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessorContract;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The MDC accessor keeps the core accessor contract and touches only its configured keys ({@code
 * specs/019-process-metadata} T032).
 */
class MdcMetadataAccessorContractTest extends MetadataAccessorContract {

  private final MdcMetadataAccessor accessor =
      new MdcMetadataAccessor(List.of("correlationId", "tenant"));

  @Override
  protected MetadataAccessor accessor() {
    return accessor;
  }

  @Override
  protected void setThreadState(Map<String, String> entries) {
    entries.forEach(MDC::put);
  }

  @Override
  protected void clearThreadState() {
    MDC.clear();
  }

  @Test
  @DisplayName("capture ignores MDC keys that are not configured")
  void captureOnlyConfiguredKeys() {
    MDC.put("correlationId", "c-1");
    MDC.put("user", "alice");

    assertThat(accessor.capture()).containsExactly(Map.entry("correlationId", "c-1"));
  }

  @Test
  @DisplayName("restore never writes a key that is not configured, and leaves other keys alone")
  void restoreOnlyConfiguredKeys() {
    MDC.put("user", "alice");

    try (MetadataAccessor.Scope scope =
        accessor.restore(SagaMetadata.of(Map.of("correlationId", "c-1", "secret", "s")))) {
      assertThat(MDC.get("correlationId")).isEqualTo("c-1");
      assertThat(MDC.get("secret")).isNull();
      assertThat(MDC.get("user")).isEqualTo("alice");
    }

    assertThat(MDC.get("user")).isEqualTo("alice");
    assertThat(MDC.get("correlationId")).isNull();
  }

  @Test
  @DisplayName("restore removes a configured key the metadata lacks, and close puts it back")
  void restoreRemovesMissingKeys() {
    MDC.put("tenant", "request-tenant");

    try (MetadataAccessor.Scope scope =
        accessor.restore(SagaMetadata.of(Map.of("correlationId", "c-1")))) {
      assertThat(MDC.get("tenant")).isNull();
    }

    assertThat(MDC.get("tenant")).isEqualTo("request-tenant");
  }
}
