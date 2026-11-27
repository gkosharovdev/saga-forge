package io.github.gkosharovdev.sagaforge.spring.metadata;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Process metadata over SLF4J's {@link MDC}, for the configured keys only ({@code
 * specs/019-process-metadata} contracts/starter-and-fixture.md): {@link #capture()} reads each key
 * that has a value; {@link #restore(SagaMetadata)} puts each key the metadata holds and removes the
 * others, and its scope puts back what the thread had. A key the metadata holds that is not
 * configured is never written to the MDC.
 *
 * <p>Immutable; thread-safe (the MDC is per thread).
 */
final class MdcMetadataAccessor implements MetadataAccessor {

  private final List<String> keys;

  MdcMetadataAccessor(List<String> keys) {
    this.keys = List.copyOf(keys);
  }

  /** The configured keys, in configuration order. */
  List<String> keys() {
    return keys;
  }

  @Override
  public Map<String, String> capture() {
    Map<String, String> captured = new LinkedHashMap<>();
    for (String key : keys) {
      String value = MDC.get(key);
      if (value != null) {
        captured.put(key, value);
      }
    }
    return captured;
  }

  @Override
  public Scope restore(SagaMetadata metadata) {
    if (metadata == null) {
      throw new IllegalArgumentException("metadata must not be null");
    }
    Map<String, String> previous = new HashMap<>();
    for (String key : keys) {
      previous.put(key, MDC.get(key));
      metadata.get(key).ifPresentOrElse(value -> MDC.put(key, value), () -> MDC.remove(key));
    }
    return () -> previous.forEach(MdcMetadataAccessor::putBack);
  }

  private static void putBack(String key, String value) {
    if (value == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }
  }
}
