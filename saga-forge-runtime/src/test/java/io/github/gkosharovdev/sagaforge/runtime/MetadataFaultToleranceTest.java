package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.ThreadLocalMetadataAccessor;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.NotifyCourier;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.Parcel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelDelivered;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelPicked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.PrintLabel;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 3 ({@code specs/019-process-metadata} T037, SC-005): a failing accessor or invalid
 * entries never fail a saga. A throwing capture starts the instance with empty metadata and reports
 * once; a throwing restore or close lets the work run and commit and reports once per unit of work;
 * an entry over the bounds is dropped and reported by key, never by value.
 */
class MetadataFaultToleranceTest {

  private static final SagaMetadata C1 = SagaMetadata.of(Map.of("correlationId", "c-1"));

  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final TransactionalInMemoryStorageProvider storage =
      new TransactionalInMemoryStorageProvider(boundary);
  private final RecordingLogger logger = new RecordingLogger();

  @BeforeEach
  void setUp() {
    MetadataModel.reset(new ThreadLocalMetadataAccessor());
  }

  private SagaRuntime engine(MetadataAccessor accessor) {
    SagaRuntime engine =
        SagaRuntime.builder()
            .register(ParcelSaga.class, ParcelService.class, Parcel.class)
            .register(PrintLabel.class, NotifyCourier.class)
            .storage(storage)
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .outbox(CapturingOutbox.inTransaction(boundary).supportingCommands())
            .handlerInstances(new MapHandlerInstanceProvider().register(new ParcelService()))
            .metadataAccessor(accessor)
            .logger(logger)
            .build();
    engine.start();
    return engine;
  }

  private SagaStatus run(SagaRuntime engine) {
    String sagaId = engine.receive(new ParcelBooked("p-1", 0)).results().get(0).sagaId();
    engine.receive(new ParcelPicked("p-1"));
    engine.receive(new ParcelDelivered("p-1"));
    return storage.committed().findById(sagaId).orElseThrow().status();
  }

  @Test
  @DisplayName("a throwing capture: the saga completes with empty metadata, reported once")
  void captureThrows() {
    SagaRuntime engine =
        engine(
            new MetadataAccessor() {
              @Override
              public Map<String, String> capture() {
                throw new IllegalStateException("capture broke");
              }

              @Override
              public Scope restore(SagaMetadata metadata) {
                return Scope.NOOP;
              }
            });
    String sagaId = engine.receive(new ParcelBooked("p-1", 0)).results().get(0).sagaId();

    assertThat(storage.committed().findById(sagaId).orElseThrow().metadata())
        .isEqualTo(SagaMetadata.empty());
    assertThat(logger.byEvent("saga.metadata.capture.failed")).hasSize(1);
  }

  @Test
  @DisplayName("a throwing restore: every unit of work runs and commits, one report each")
  void restoreThrows() {
    int[] restores = {0};
    SagaRuntime engine =
        engine(
            new MetadataAccessor() {
              @Override
              public Map<String, String> capture() {
                return C1.asMap();
              }

              @Override
              public Scope restore(SagaMetadata metadata) {
                restores[0]++;
                throw new IllegalStateException("restore broke");
              }
            });

    assertThat(run(engine)).isEqualTo(SagaStatus.COMPLETED);
    assertThat(restores[0]).isPositive();
    assertThat(logger.byEvent("saga.metadata.restore.failed")).hasSize(restores[0]);
  }

  @Test
  @DisplayName("a throwing close: every unit of work runs and commits, one report each")
  void closeThrows() {
    int[] closes = {0};
    SagaRuntime engine =
        engine(
            new MetadataAccessor() {
              @Override
              public Map<String, String> capture() {
                return C1.asMap();
              }

              @Override
              public Scope restore(SagaMetadata metadata) {
                return () -> {
                  closes[0]++;
                  throw new IllegalStateException("close broke");
                };
              }
            });

    assertThat(run(engine)).isEqualTo(SagaStatus.COMPLETED);
    assertThat(closes[0]).isPositive();
    assertThat(logger.byEvent("saga.metadata.restore.failed")).hasSize(closes[0]);
  }

  @Test
  @DisplayName("entries over the bounds are dropped and reported by key, never by value")
  void overBoundEntries() {
    Map<String, String> captured = new LinkedHashMap<>();
    captured.put("correlationId", "c-1");
    captured.put("k".repeat(SagaMetadata.MAX_KEY_LENGTH + 1), "v");
    captured.put("big", "secret-" + "x".repeat(SagaMetadata.MAX_VALUE_LENGTH));
    for (int index = 0; index < SagaMetadata.MAX_ENTRIES; index++) {
      captured.put("extra-" + index, "e");
    }
    SagaRuntime engine =
        engine(
            new MetadataAccessor() {
              @Override
              public Map<String, String> capture() {
                return captured;
              }

              @Override
              public Scope restore(SagaMetadata metadata) {
                return Scope.NOOP;
              }
            });

    String sagaId = engine.receive(new ParcelBooked("p-1", 0)).results().get(0).sagaId();

    SagaMetadata stored = storage.committed().findById(sagaId).orElseThrow().metadata();
    assertThat(stored.asMap())
        .hasSize(SagaMetadata.MAX_ENTRIES)
        .containsEntry("correlationId", "c-1");
    assertThat(stored.asMap()).doesNotContainKey("big");
    assertThat(logger.byEvent("saga.metadata.entry.dropped"))
        .isNotEmpty()
        .allSatisfy(logged -> assertThat(logged.message()).doesNotContain("secret-"))
        .anySatisfy(logged -> assertThat(logged.message()).contains("key=big"));
  }
}
