package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import java.util.Map;

/**
 * Test double of {@link MetadataAccessor} over a thread-local map, standing in for a logging
 * context ({@code specs/019-process-metadata} contracts/metadata-spi.md). Shipped in the core
 * test-jar for the runtime, transport and fixture tests.
 */
public final class ThreadLocalMetadataAccessor implements MetadataAccessor {

  private final ThreadLocal<Map<String, String>> current = ThreadLocal.withInitial(Map::of);

  /**
   * Sets the current thread's entries, as a request filter would.
   *
   * @param entries the entries; must not be {@code null}
   */
  public void set(Map<String, String> entries) {
    current.set(Map.copyOf(entries));
  }

  /** Clears the current thread's entries. */
  public void clear() {
    current.remove();
  }

  @Override
  public Map<String, String> capture() {
    return current.get();
  }

  @Override
  public Scope restore(SagaMetadata metadata) {
    if (metadata == null) {
      throw new IllegalArgumentException("metadata must not be null");
    }
    Map<String, String> previous = current.get();
    current.set(metadata.asMap());
    return () -> current.set(previous);
  }
}
