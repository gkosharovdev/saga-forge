package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import java.util.Map;

/**
 * SPI between Saga Forge's process metadata and the host's own thread context, such as a logging
 * context ({@code specs/019-process-metadata} contracts/metadata-spi.md). Modelled on Micrometer
 * Context Propagation's {@code ThreadLocalAccessor}, with a scope like OpenTelemetry's.
 *
 * <p><strong>Implemented by host integrations</strong> (the Spring Boot starter's MDC accessor, a
 * host that assembles the runtime itself, test doubles); called only by the runtime, the transport
 * and the test fixture:
 *
 * <ul>
 *   <li>{@link #capture()} on the thread that produces a message, or that hands an event to the
 *       runtime, outside any delivery. The caller bounds the entries ({@link
 *       SagaMetadata#sanitized}); a thrown exception gives empty metadata and a diagnostic.
 *   <li>{@link #restore(SagaMetadata)} at the start of each unit of work and each delivery; the
 *       returned scope is closed in {@code finally} on the same thread. A thrown exception is
 *       reported and the work runs without it.
 * </ul>
 *
 * <p>Scopes may nest: closing the inner scope must put back what the outer scope restored.
 */
public interface MetadataAccessor {

  /** Captures no entries and restores nothing. */
  MetadataAccessor NONE =
      new MetadataAccessor() {
        @Override
        public Map<String, String> capture() {
          return Map.of();
        }

        @Override
        public Scope restore(SagaMetadata metadata) {
          if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
          }
          return Scope.NOOP;
        }

        @Override
        public String toString() {
          return "MetadataAccessor.NONE";
        }
      };

  /**
   * The current thread's metadata entries, as the host holds them. The caller bounds them with
   * {@link SagaMetadata#sanitized} and reports the entries it drops.
   *
   * @return the entries; never {@code null}, possibly empty
   */
  Map<String, String> capture();

  /**
   * Makes {@code metadata} current on this thread until the returned scope is closed.
   *
   * @param metadata the metadata to restore; must not be {@code null}
   * @return the scope; never {@code null}
   * @throws IllegalArgumentException if {@code metadata} is {@code null}
   */
  Scope restore(SagaMetadata metadata);

  /** Puts back what the thread held before the matching {@link #restore(SagaMetadata)}. */
  interface Scope extends AutoCloseable {

    /** A scope whose {@link #close()} does nothing. */
    Scope NOOP = () -> {};

    /** Puts back the thread's earlier state; called once, on the thread that opened the scope. */
    @Override
    void close();
  }
}
