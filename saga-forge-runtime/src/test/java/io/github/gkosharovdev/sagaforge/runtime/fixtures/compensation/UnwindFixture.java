package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An engine over {@link UnwindSaga} or {@link UnwindWithoutBSaga}. {@link #restart()} builds a new
 * engine over the same stores, as a process restart would.
 */
public final class UnwindFixture {

  public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private final Class<?> sagaType;
  private final Function<Journal, Object> sagas;
  private final Journal journal = new Journal();
  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final TransactionalInMemoryStorageProvider storage =
      new TransactionalInMemoryStorageProvider(boundary);
  private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
  private final RecordingLogger logger = new RecordingLogger();
  private final AtomicInteger sagaIds = new AtomicInteger();
  private final CapturingOutbox outbox = CapturingOutbox.afterCommit();
  private final java.util.concurrent.atomic.AtomicReference<Instant> now =
      new java.util.concurrent.atomic.AtomicReference<>(NOW);
  private final Clock clock =
      new Clock() {
        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };
  private io.github.gkosharovdev.sagaforge.runtime.RecoverySettings recovery =
      io.github.gkosharovdev.sagaforge.runtime.RecoverySettings.defaults();
  private SagaRuntime engine;

  private UnwindFixture(
      Class<?> sagaType, Function<Journal, Object> sagas, StorageProvider overStorage) {
    this.sagaType = sagaType;
    this.sagas = sagas;
    this.engine = build(overStorage);
  }

  public static UnwindFixture withEveryCompensation() {
    return new UnwindFixture(UnwindSaga.class, UnwindSaga::new, null);
  }

  public static UnwindFixture withoutCompensationForB() {
    return new UnwindFixture(UnwindWithoutBSaga.class, UnwindWithoutBSaga::new, null);
  }

  /**
   * Replaces the engine with one whose storage calls go through {@code faults}, wrapping this
   * fixture's transactional store; {@link #restart()} goes back to the plain store.
   */
  public CompensationFaults withFaults() {
    CompensationFaults faults = new CompensationFaults(storage);
    engine = build(faults);
    return faults;
  }

  private SagaRuntime build(StorageProvider overStorage) {
    SagaRuntime built =
        SagaRuntime.builder()
            .register(sagaType, ChainService.class, Order.class)
            .storage(overStorage == null ? storage : overStorage)
            .contextStore(contexts)
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .outbox(outbox)
            .handlerInstances(new MapHandlerInstanceProvider().register(new ChainService()))
            .sagaObjects(
                new SagaObjectProvider() {
                  @Override
                  public boolean supports(Class<?> type) {
                    return type == sagaType;
                  }

                  @Override
                  public Object newInstance(Class<?> type) {
                    return sagas.apply(journal);
                  }
                })
            .clock(clock)
            .recovery(recovery)
            .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
            .logger(logger)
            .build();
    built.start();
    return built;
  }

  /** Moves this fixture's clock forward by {@code elapsed}. */
  public UnwindFixture advance(java.time.Duration elapsed) {
    now.updateAndGet(at -> at.plus(elapsed));
    return this;
  }

  /** The fixture clock's current instant. */
  public Instant now() {
    return now.get();
  }

  /** Rebuilds the engine with {@code settings} (015); the old engine is stopped. */
  public UnwindFixture withRecovery(
      io.github.gkosharovdev.sagaforge.runtime.RecoverySettings settings) {
    engine.stop();
    this.recovery = settings;
    engine = build(null);
    return this;
  }

  /** The engine's operations API (015). */
  public io.github.gkosharovdev.sagaforge.core.operations.SagaOperations operations() {
    return engine.operations();
  }

  /** The current engine. */
  public SagaRuntime engine() {
    return engine;
  }

  /** The context store the engine uses. */
  public InMemorySagaContextStore contexts() {
    return contexts;
  }

  /** A new engine over the same stores; the old one is no longer used. */
  public UnwindFixture restart() {
    engine = build(null);
    return this;
  }

  public SagaHandlingResult receive(Object event) {
    HandlingReport report = engine.receive(event);
    if (report.results().size() != 1) {
      throw new IllegalStateException("Expected one result, got " + report.results());
    }
    return report.results().get(0);
  }

  public String start(String orderId) {
    return receive(new UnwindStarted(orderId)).sagaId();
  }

  public SagaInstance committed(String sagaId) {
    return storage.committed().findById(sagaId).orElseThrow();
  }

  public Journal journal() {
    return journal;
  }

  public TransactionalInMemoryStorageProvider storage() {
    return storage;
  }

  /** Every batch the engine's units of work handed on (011: outcome events included). */
  public CapturingOutbox outbox() {
    return outbox;
  }

  public RecordingLogger logger() {
    return logger;
  }
}
