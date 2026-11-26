package io.github.gkosharovdev.sagaforge.runtime.fixtures.commandfailure;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepCompensation;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.CompensationFaults;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shipped runtime over the in-memory test doubles with {@link ParcelSaga} registered, for the
 * 012 tests: drives a parcel to the point where {@code pack} sent {@link ShipParcel}, and reports
 * failures through {@link TestTransport} as a transport would.
 */
public final class ParcelFixture {

  /** The cause type the reports carry. */
  public static final String CAUSE = "com.example.CourierRefused";

  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final TransactionalInMemoryStorageProvider storage =
      new TransactionalInMemoryStorageProvider(boundary);
  private final CapturingOutbox outbox =
      CapturingOutbox.inTransaction(boundary).supportingCommands();
  private final RecordingLogger logger = new RecordingLogger();
  private final TestTransport transport = new TestTransport();
  private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
  private final OpenTelemetry openTelemetry;
  private io.github.gkosharovdev.sagaforge.runtime.RecoverySettings recovery =
      io.github.gkosharovdev.sagaforge.runtime.RecoverySettings.defaults();
  private SagaRuntime engine;

  public ParcelFixture() {
    this(OpenTelemetry.noop());
  }

  /** With the runtime instrumented through {@code openTelemetry}. */
  public ParcelFixture(OpenTelemetry openTelemetry) {
    ParcelSaga.reset();
    this.openTelemetry = openTelemetry;
    engine = build(storage);
  }

  private SagaRuntime build(StorageProvider overStorage) {
    SagaRuntime built =
        SagaRuntime.builder()
            .openTelemetry(openTelemetry)
            .register(ParcelSaga.class, ParcelService.class, Parcel.class)
            .storage(overStorage)
            .contextStore(contexts)
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .outbox(outbox)
            .handlerInstances(new MapHandlerInstanceProvider().register(new ParcelService()))
            .logger(logger)
            .recovery(recovery)
            .build();
    built.start();
    transport.connect(built).connectCommands(built.commandReceiver());
    return built;
  }

  /**
   * Rebuilds the runtime over a storage that crashes on command, over the same stores; see {@link
   * #restart()}.
   */
  public CompensationFaults withFaults() {
    CompensationFaults faults = new CompensationFaults(storage);
    engine = build(faults);
    return faults;
  }

  /** Rebuilds the runtime over the plain stores with {@code settings} (015). */
  public ParcelFixture withRecovery(
      io.github.gkosharovdev.sagaforge.runtime.RecoverySettings settings) {
    engine.stop();
    recovery = settings;
    engine = build(storage);
    return this;
  }

  /** Rebuilds the runtime over the plain stores, as a process restart would. */
  public ParcelFixture restart() {
    engine = build(storage);
    return this;
  }

  /** Books, pays and packs {@code parcelId}; returns the instance's id. */
  public String packed(String parcelId) {
    String sagaId = engine.receive(new ParcelBooked(parcelId)).results().get(0).sagaId();
    engine.receive(new ParcelPaid(parcelId));
    engine.receive(new ParcelPacked(parcelId));
    return sagaId;
  }

  /**
   * Puts the committed instance {@code sagaId} into {@code COMPENSATING} with a {@code
   * FINAL_FAILURE} trigger on {@code deliver}, as if a compensation run stopped part-way: the
   * completed steps in {@code compensated} are {@code COMPENSATED}, the others {@code
   * NOT_ATTEMPTED}. Written directly, outside any unit of work.
   */
  public void compensating(String sagaId, Set<String> compensated) {
    SagaInstance current = committed(sagaId);
    Instant now = Instant.now();
    Map<String, StepRecord> steps = new HashMap<>();
    current
        .steps()
        .forEach(
            (name, step) ->
                steps.put(
                    name,
                    step.status() != StepStatus.COMPLETED
                        ? step
                        : step.withCompensation(
                            compensated.contains(name)
                                ? StepCompensation.compensated(now)
                                : StepCompensation.notAttempted())));
    CompensationTrigger trigger =
        CompensationTrigger.failed(
            new SagaFailure("deliver", CAUSE, null, now, FailureClassification.FINAL, 1), now);
    storage.save(
        new SagaInstance(
            current.sagaId(),
            current.sagaType(),
            Map.of(),
            current.associations(),
            SagaStatus.COMPENSATING,
            current.createdAt(),
            now,
            steps,
            null,
            null,
            null,
            trigger),
        List.of());
  }

  /** Delivers an event to the runtime. */
  public void receive(Object event) {
    engine.receive(event);
  }

  /** The identity of the first committed command of {@code type}. */
  public CommandId sent(Class<?> type) {
    return commands().stream()
        .filter(command -> type.isInstance(command.command()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " was committed"))
        .commandId();
  }

  /** A report of {@code commandId} with {@link #CAUSE}. */
  public static CommandFailure report(
      CommandId commandId, FailureClassification classification, int attempts) {
    return new CommandFailure(commandId, classification, CAUSE, attempts);
  }

  public SagaInstance committed(String sagaId) {
    return storage.committed().findById(sagaId).orElseThrow();
  }

  public List<OutgoingCommand> commands() {
    return outbox.committed().stream().flatMap(batch -> batch.commands().stream()).toList();
  }

  public List<SagaOutcome> outcomes() {
    return outbox.outcomes();
  }

  public TestTransport transport() {
    return transport;
  }

  public TransactionalInMemoryStorageProvider storage() {
    return storage;
  }

  public RecordingLogger logger() {
    return logger;
  }

  public SagaRuntime engine() {
    return engine;
  }
}
