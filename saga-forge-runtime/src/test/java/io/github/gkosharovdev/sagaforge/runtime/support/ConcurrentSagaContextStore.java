package io.github.gkosharovdev.sagaforge.runtime.support;

import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SagaContextStore} over a {@link ConcurrentHashMap}, for the engine's concurrency tests;
 * core's {@code InMemorySagaContextStore} is not thread-safe. Like every context store it never
 * joins a transaction, so values survive a unit of work's rollback.
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never
 * move to {@code src/main} (Constitution Principle IV).
 *
 * <p>Thread-safe.
 */
public final class ConcurrentSagaContextStore implements SagaContextStore {

  private final Map<String, SagaContextRecord> records = new ConcurrentHashMap<>();

  @Override
  public Optional<SagaContextRecord> find(String sagaId) {
    requireSagaId(sagaId);
    return Optional.ofNullable(records.get(sagaId));
  }

  @Override
  public void save(SagaContextRecord record) {
    if (record == null) {
      throw new IllegalArgumentException("record must not be null");
    }
    records.put(record.sagaId(), record);
  }

  @Override
  public void delete(String sagaId) {
    requireSagaId(sagaId);
    records.remove(sagaId);
  }

  private static void requireSagaId(String sagaId) {
    if (sagaId == null) {
      throw new IllegalArgumentException("sagaId must not be null");
    }
  }
}
