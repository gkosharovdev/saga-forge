package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import java.util.Map;

/**
 * The runtime's only caller of the host's {@link MetadataAccessor} ({@code
 * specs/019-process-metadata} contracts/metadata-spi.md): captures are sanitised and a capture
 * failure gives empty metadata; restore scopes are opened and closed so that an accessor failure
 * never fails the work around it. Each failure and each dropped entry is a diagnostic naming keys
 * only, never values (FR-019, FR-024).
 *
 * <p>Immutable; thread-safe if the accessor is.
 */
final class MetadataScopes {

  private final MetadataAccessor accessor;
  private final Diagnostics diagnostics;

  MetadataScopes(MetadataAccessor accessor, Diagnostics diagnostics) {
    this.accessor = accessor;
    this.diagnostics = diagnostics;
  }

  /** The calling thread's metadata, sanitised; empty if the accessor throws. */
  SagaMetadata capture() {
    Map<String, String> captured;
    try {
      captured = accessor.capture();
    } catch (RuntimeException failure) {
      diagnostics.metadataCaptureFailed(failure);
      return SagaMetadata.empty();
    }
    return SagaMetadata.sanitized(captured, diagnostics::metadataEntryDropped);
  }

  /**
   * Makes {@code metadata} the calling thread's until the returned scope is closed; a failing
   * restore gives a scope that does nothing, a failing close is logged.
   */
  MetadataAccessor.Scope restore(SagaMetadata metadata) {
    MetadataAccessor.Scope opened;
    try {
      opened = accessor.restore(metadata == null ? SagaMetadata.empty() : metadata);
    } catch (RuntimeException failure) {
      diagnostics.metadataRestoreFailed(failure);
      return MetadataAccessor.Scope.NOOP;
    }
    if (opened == null) {
      return MetadataAccessor.Scope.NOOP;
    }
    return () -> {
      try {
        opened.close();
      } catch (RuntimeException failure) {
        diagnostics.metadataRestoreFailed(failure);
      }
    };
  }
}
