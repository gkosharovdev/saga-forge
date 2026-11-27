package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RecoveryReport;
import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.EventDelivery;
import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.ThreadLocalMetadataAccessor;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CompensationFaults;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.LabelPrinted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.LabelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.NotifyCourier;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.Parcel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelLost;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelPicked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.Seen;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Capture, propagation and restoration of process metadata in the runtime ({@code
 * specs/019-process-metadata} contracts/propagation.md, User Story 1 scenarios 1 to 5 and User
 * Story 3 at runtime level).
 */
class MetadataPropagationTest {

  private static final SagaMetadata C1 = SagaMetadata.of(Map.of("correlationId", "c-1"));
  private static final SagaMetadata C2 = SagaMetadata.of(Map.of("correlationId", "c-2"));

  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final TransactionalInMemoryStorageProvider storage =
      new TransactionalInMemoryStorageProvider(boundary);
  private final CapturingOutbox outbox =
      CapturingOutbox.inTransaction(boundary).supportingCommands();
  private final ThreadLocalMetadataAccessor accessor = new ThreadLocalMetadataAccessor();
  private final RecordingLogger logger = new RecordingLogger();
  private final InMemorySagaContextStore contextStore = new InMemorySagaContextStore();

  @BeforeEach
  void setUp() {
    MetadataModel.reset(accessor);
  }

  private SagaRuntime engine(MetadataAccessor metadataAccessor) {
    return engine(metadataAccessor, storage, outbox, Clock.systemUTC());
  }

  private SagaRuntime engine(
      MetadataAccessor metadataAccessor,
      StorageProvider storageProvider,
      CapturingOutbox capturingOutbox,
      Clock clock) {
    SagaRuntime engine =
        SagaRuntime.builder()
            .register(ParcelSaga.class, LabelSaga.class, ParcelService.class, Parcel.class)
            .register(MetadataModel.PrintLabel.class, NotifyCourier.class)
            .storage(storageProvider)
            .contextStore(contextStore)
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .outbox(capturingOutbox)
            .handlerInstances(new MapHandlerInstanceProvider().register(new ParcelService()))
            .metadataAccessor(metadataAccessor)
            .logger(logger)
            .clock(clock)
            .build();
    engine.start();
    return engine;
  }

  private OutgoingCommand sentNotify(CapturingOutbox capturingOutbox) {
    return capturingOutbox.committed().stream()
        .flatMap(batch -> batch.commands().stream())
        .filter(command -> command.command() instanceof NotifyCourier)
        .findFirst()
        .orElseThrow();
  }

  private List<SagaLifecycleEntry> entriesSince(String sagaId, int before) {
    List<SagaLifecycleEntry> history = storage.history(sagaId);
    return history.subList(before, history.size());
  }

  private String start(SagaRuntime engine, ParcelBooked event) {
    accessor.set(C1.asMap());
    try {
      return engine.receive(event).results().get(0).sagaId();
    } finally {
      accessor.clear();
    }
  }

  private List<Seen> seenAt(String where) {
    return MetadataModel.SEEN.stream().filter(seen -> seen.where().equals(where)).toList();
  }

  @Test
  @DisplayName(
      "scenario 1: the start captures the caller's metadata; steps and handlers see it restored")
  void startCapturesAndRestores() {
    String sagaId = start(engine(accessor), new ParcelBooked("p-1", 0));

    assertThat(storage.committed().findById(sagaId).orElseThrow().metadata()).isEqualTo(C1);
    assertThat(seenAt("onBooked"))
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.injected()).isEqualTo(C1);
              assertThat(seen.thread()).isEqualTo(C1.asMap());
            });
    assertThat(seenAt("print")).singleElement().extracting(Seen::injected).isEqualTo(C1);
    assertThat(storage.history(sagaId)).extracting(SagaLifecycleEntry::metadata).containsOnly(C1);
    assertThat(outbox.committed()).extracting(OutgoingMessages::metadata).containsOnly(C1);
    assertThat(accessor.capture()).isEmpty();
  }

  @Test
  @DisplayName(
      "scenario 2: a later event keeps the instance's metadata and records its own on the entry")
  void laterEventKeepsInstanceMetadata() {
    SagaRuntime engine = engine(accessor);
    String sagaId = start(engine, new ParcelBooked("p-1", 0));
    int entriesBefore = storage.history(sagaId).size();

    engine.receive(new EventDelivery(new ParcelPicked("p-1"), null, C2));

    assertThat(storage.committed().findById(sagaId).orElseThrow().metadata()).isEqualTo(C1);
    assertThat(seenAt("onPicked"))
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.injected()).isEqualTo(C1);
              assertThat(seen.thread()).isEqualTo(C1.asMap());
            });
    assertThat(storage.history(sagaId).subList(entriesBefore, storage.history(sagaId).size()))
        .isNotEmpty()
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(C2);
    assertThat(outbox.committed().get(outbox.committed().size() - 1).metadata()).isEqualTo(C1);
  }

  @Test
  @DisplayName("scenario 3: an outbox command is handled with the metadata it was delivered with")
  void outboxCommandCarriesMetadata() {
    SagaRuntime engine = engine(accessor);
    start(engine, new ParcelBooked("p-1", 0));
    OutgoingCommand sent =
        outbox.committed().stream()
            .flatMap(batch -> batch.commands().stream())
            .filter(command -> command.command() instanceof NotifyCourier)
            .findFirst()
            .orElseThrow();

    engine
        .commandReceiver()
        .receive(new OutboxCommand(sent.commandId(), sent.command(), tx -> {}, C1));

    assertThat(seenAt("notifyCourier"))
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.injected()).isEqualTo(C1);
              assertThat(seen.thread()).isEqualTo(C1.asMap());
            });
    OutgoingMessages handlerBatch = outbox.committed().get(outbox.committed().size() - 1);
    assertThat(handlerBatch.sagaId()).isNull();
    assertThat(handlerBatch.metadata()).isEqualTo(C1);
    assertThat(accessor.capture()).isEmpty();
  }

  @Test
  @DisplayName("scenario 4: every retry attempt runs with the instance's metadata restored")
  void retriesRestoreMetadata() {
    String sagaId = start(engine(accessor), new ParcelBooked("p-1", 2));

    assertThat(seenAt("onBooked"))
        .hasSize(3)
        .allSatisfy(seen -> assertThat(seen.thread()).isEqualTo(C1.asMap()));
    assertThat(storage.history(sagaId))
        .filteredOn(entry -> entry.kind() == SagaLifecycleEventKind.ATTEMPT_FAILED)
        .hasSize(2)
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(C1);
  }

  @Test
  @DisplayName(
      "scenario 5: a compensation started by a later event sees the instance's metadata; its"
          + " entries record the event's")
  void compensationRestoresMetadata() {
    SagaRuntime engine = engine(accessor);
    String sagaId = start(engine, new ParcelBooked("p-1", 0));

    engine.receive(new EventDelivery(new ParcelLost("p-1"), null, C2));

    assertThat(storage.committed().findById(sagaId).orElseThrow().status())
        .isEqualTo(SagaStatus.COMPENSATED);
    assertThat(seenAt("undoBooked"))
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.injected()).isEqualTo(C1);
              assertThat(seen.thread()).isEqualTo(C1.asMap());
            });
    assertThat(storage.history(sagaId))
        .filteredOn(entry -> entry.kind() == SagaLifecycleEventKind.COMPENSATION_STARTED)
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(C2);
    assertThat(outbox.outcomes()).hasSize(1);
    assertThat(outbox.committed().get(outbox.committed().size() - 1).metadata()).isEqualTo(C1);
  }

  @Test
  @DisplayName("an operator repair runs with the instance's metadata and records it on its entries")
  void operatorRepairUsesInstanceMetadata() {
    SagaRuntime engine = engine(accessor);
    String sagaId = start(engine, new ParcelBooked("p-1", 0));
    int entriesBefore = storage.history(sagaId).size();

    accessor.set(C2.asMap());
    try {
      engine.operations().compensate(sagaId, new OperatorRequest("ops", "stuck"));
    } finally {
      accessor.clear();
    }

    assertThat(seenAt("undoBooked"))
        .singleElement()
        .satisfies(seen -> assertThat(seen.thread()).isEqualTo(C1.asMap()));
    assertThat(storage.history(sagaId).subList(entriesBefore, storage.history(sagaId).size()))
        .isNotEmpty()
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(C1);
  }

  @Test
  @DisplayName(
      "the thread's own state is put back after each unit of work, also when the step fails")
  void scopesUnwind() {
    SagaRuntime engine = engine(accessor);
    String sagaId = start(engine, new ParcelBooked("p-1", 0));
    Map<String, String> callerState = Map.of("request", "r-9");
    accessor.set(callerState);

    engine.receive(new ParcelPicked("p-1"));
    Map<String, String> afterStep = accessor.capture();
    engine.receive(new ParcelBooked("p-2", 99));
    Map<String, String> afterFailure = accessor.capture();
    accessor.clear();

    assertThat(seenAt("onPicked"))
        .singleElement()
        .satisfies(seen -> assertThat(seen.thread()).isEqualTo(C1.asMap()));
    assertThat(afterStep).isEqualTo(callerState);
    assertThat(afterFailure).isEqualTo(callerState);
    assertThat(storage.committed().findById(sagaId)).isPresent();
  }

  @Test
  @DisplayName(
      "with MetadataAccessor.NONE nothing is captured; delivered metadata still reaches saga code")
  void noneAccessor() {
    SagaRuntime engine = engine(MetadataAccessor.NONE);

    String sagaId =
        engine
            .receive(new EventDelivery(new ParcelBooked("p-1", 0), null, C1))
            .results()
            .get(0)
            .sagaId();
    engine.receive(new ParcelPicked("p-1"));

    assertThat(storage.committed().findById(sagaId).orElseThrow().metadata()).isEqualTo(C1);
    assertThat(seenAt("onBooked")).extracting(Seen::injected).containsOnly(C1);
    assertThat(seenAt("print")).extracting(Seen::injected).containsOnly(C1);
    assertThat(storage.history(sagaId))
        .filteredOn(entry -> entry.stepName() != null && entry.stepName().equals("onPicked"))
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(SagaMetadata.empty());
  }

  @Test
  @DisplayName(
      "User Story 3: a failing accessor never fails the saga; invalid entries are dropped by key")
  void faultTolerance() {
    MetadataAccessor failing =
        new MetadataAccessor() {
          @Override
          public Map<String, String> capture() {
            Map<String, String> captured = new HashMap<>();
            captured.put("correlationId", "c-1");
            captured.put("secret", "x".repeat(SagaMetadata.MAX_VALUE_LENGTH + 1));
            return captured;
          }

          @Override
          public Scope restore(SagaMetadata metadata) {
            throw new IllegalStateException("restore broke");
          }
        };
    SagaRuntime engine = engine(failing);

    String sagaId = engine.receive(new ParcelBooked("p-1", 0)).results().get(0).sagaId();

    assertThat(storage.committed().findById(sagaId).orElseThrow().metadata()).isEqualTo(C1);
    assertThat(logger.byEvent("saga.metadata.entry.dropped"))
        .singleElement()
        .satisfies(
            logged -> {
              assertThat(logged.message()).contains("key=secret").doesNotContain("xxxx");
            });
    assertThat(logger.byEvent("saga.metadata.restore.failed")).isNotEmpty();
  }

  @Test
  @DisplayName(
      "a failed outbox command's compensation runs with the instance's metadata, not the"
          + " reporting thread's, and records it on its entries")
  void failedOutboxCommandCompensatesWithInstanceMetadata() {
    SagaRuntime engine = engine(accessor);
    String sagaId = start(engine, new ParcelBooked("p-1", 0));
    OutgoingCommand sent = sentNotify(outbox);
    int entriesBefore = storage.history(sagaId).size();

    Map<String, String> afterReport;
    accessor.set(C2.asMap());
    try {
      engine
          .commandReceiver()
          .receiveFailure(
              new CommandFailure(
                  sent.commandId(), FailureClassification.FINAL, "com.example.CourierRefused", 1),
              tx -> {});
      afterReport = accessor.capture();
    } finally {
      accessor.clear();
    }

    assertThat(storage.committed().findById(sagaId).orElseThrow().status())
        .isEqualTo(SagaStatus.COMPENSATED);
    assertThat(seenAt("undoBooked"))
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.injected()).isEqualTo(C1);
              assertThat(seen.thread()).isEqualTo(C1.asMap());
            });
    assertThat(entriesSince(sagaId, entriesBefore))
        .isNotEmpty()
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(C1);
    assertThat(outbox.committed().get(outbox.committed().size() - 1).metadata()).isEqualTo(C1);
    assertThat(afterReport).isEqualTo(C2.asMap());
  }

  @Test
  @DisplayName(
      "a recovery sweep resumes a stalled compensation with the instance's metadata and records"
          + " it on its entries")
  void recoverySweepResumesWithInstanceMetadata() {
    Instant crashedAt = Instant.parse("2026-11-27T10:00:00Z");
    CompensationFaults faults = new CompensationFaults(storage);
    SagaRuntime crashing = engine(accessor, faults, outbox, Clock.fixed(crashedAt, ZoneOffset.UTC));
    String sagaId = start(crashing, new ParcelBooked("p-1", 0));
    faults.crashOnLock(2);
    assertThat(
            catchThrowable(
                () -> crashing.receive(new EventDelivery(new ParcelLost("p-1"), null, C2))))
        .isNotNull();
    assertThat(storage.committed().findById(sagaId).orElseThrow().status())
        .isEqualTo(SagaStatus.COMPENSATING);
    assertThat(seenAt("undoBooked")).isEmpty();
    int entriesBefore = storage.history(sagaId).size();
    SagaRuntime restarted =
        engine(
            accessor,
            storage,
            outbox,
            Clock.fixed(crashedAt.plus(Duration.ofHours(1)), ZoneOffset.UTC));

    RecoveryReport report;
    Map<String, String> afterSweep;
    accessor.set(Map.of("sweep", "s-1"));
    try {
      report = restarted.operations().recover();
      afterSweep = accessor.capture();
    } finally {
      accessor.clear();
    }

    assertThat(report.compensationsResumed()).isEqualTo(1);
    assertThat(storage.committed().findById(sagaId).orElseThrow().status())
        .isEqualTo(SagaStatus.COMPENSATED);
    assertThat(seenAt("undoBooked"))
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.injected()).isEqualTo(C1);
              assertThat(seen.thread()).isEqualTo(C1.asMap());
            });
    assertThat(entriesSince(sagaId, entriesBefore))
        .isNotEmpty()
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(C1);
    assertThat(afterSweep).isEqualTo(Map.of("sweep", "s-1"));
  }

  @Test
  @DisplayName(
      "a saga started by another saga's recorded event takes the metadata that event was stored"
          + " with")
  void fanOutStartTakesTheSourceMetadata() {
    SagaRuntime engine = engine(accessor);
    start(engine, new ParcelBooked("p-1", 0));
    OutgoingCommand sent = sentNotify(outbox);
    engine
        .commandReceiver()
        .receive(new OutboxCommand(sent.commandId(), sent.command(), tx -> {}, C1));
    OutgoingMessages printed =
        outbox.committed().stream()
            .filter(batch -> batch.events().stream().anyMatch(LabelPrinted.class::isInstance))
            .findFirst()
            .orElseThrow();
    Object event =
        printed.events().stream().filter(LabelPrinted.class::isInstance).findFirst().orElseThrow();

    String labelSagaId;
    accessor.set(C2.asMap());
    try {
      labelSagaId =
          engine
              .receive(new EventDelivery(event, printed.sagaId(), printed.metadata()))
              .results()
              .get(0)
              .sagaId();
    } finally {
      accessor.clear();
    }

    assertThat(printed.metadata()).isEqualTo(C1);
    assertThat(storage.committed().findById(labelSagaId).orElseThrow().metadata()).isEqualTo(C1);
    assertThat(seenAt("onLabelPrinted"))
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.injected()).isEqualTo(C1);
              assertThat(seen.thread()).isEqualTo(C1.asMap());
            });
    assertThat(storage.history(labelSagaId))
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(C1);
  }

  @Test
  @DisplayName(
      "an outbox that observes recorded events sees the metadata restored while it stores them")
  void recordedEventListenerRunsInsideTheScope() {
    List<Map<String, String>> atStore = new CopyOnWriteArrayList<>();
    CapturingOutbox listening =
        CapturingOutbox.inTransaction(
                boundary,
                batch -> {
                  if (!batch.recordedEvents().isEmpty()) {
                    atStore.add(accessor.capture());
                  }
                })
            .supportingCommands()
            .observingRecordedEvents();
    SagaRuntime engine = engine(accessor, storage, listening, Clock.systemUTC());
    start(engine, new ParcelBooked("p-1", 0));
    OutgoingCommand sent = sentNotify(listening);

    Map<String, String> afterDelivery;
    accessor.set(C2.asMap());
    try {
      engine
          .commandReceiver()
          .receive(new OutboxCommand(sent.commandId(), sent.command(), tx -> {}, C1));
      afterDelivery = accessor.capture();
    } finally {
      accessor.clear();
    }

    assertThat(atStore).isNotEmpty().containsOnly(C1.asMap());
    assertThat(afterDelivery).isEqualTo(C2.asMap());
  }
}
