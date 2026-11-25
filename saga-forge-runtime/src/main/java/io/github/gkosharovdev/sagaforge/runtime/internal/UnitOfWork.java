package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.SagaConcurrencyConflictException;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.EngineSagaLifecycle.PendingWrite;
import io.opentelemetry.api.common.Attributes;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One host transaction around one piece of {@link Work} (specs/011-saga-outcome-events
 * contracts/unit-of-work.md).
 *
 * <p>The unit of work owns everything about the transaction and nothing about what runs in it: it
 * begins the transaction and marks the thread "unit of work running" ({@link ReentrancyGuard}); in
 * instance scope it locks the instance with {@code StorageProvider.findByIdAndLock} as the first
 * storage call, lets the work {@linkplain Work#admit(Optional) admit} the locked instance, and
 * loads it into the lifecycle; it binds itself as the thread's domain-event recorder while the work
 * runs user code; it writes the lifecycle's drained change with {@code StorageProvider.save}; it
 * builds the outgoing batch and hands it to the {@link Outbox} at the outbox's timing; and it
 * commits, or rolls back and discards. Which phase of a saga it serves (running steps, retrying,
 * failing, compensating, handling a command) is the work's business and the business of the class
 * that chose the work.
 *
 * <h2>{@link #run(Work)}</h2>
 *
 * <ol>
 *   <li>{@code begin()}; mark the thread.
 *   <li>Instance scope: {@code findByIdAndLock(sagaId)}. A failure of this read rolls back and
 *       propagates: nothing was recorded.
 *   <li>Instance scope: {@code work.admit(locked)}. An {@link Admission.Skip} rolls back and is
 *       returned as {@link Skipped} with its reason.
 *   <li>Load the lifecycle with the locked instance.
 *   <li>{@code work.perform(context)}; the work binds this unit of work as the thread's recorder
 *       around user code ({@link WorkContext#recording(Runnable)}).
 *   <li>A reported failure, or a transaction found rollback-only, fails the unit of work.
 *   <li>Instance scope: drain the lifecycle; cancel the scheduled messages of the deadlines it
 *       closed with {@code Outbox.cancel(ids)}, inside the transaction (017); then {@code
 *       save(instance, entries)}.
 *   <li>Build the batch: every recorded event the work did not run itself, in recording order, then
 *       the outcome event of the drained write if it has one (011; unless outcome events are
 *       switched off); the outbox commands, in send order; every recorded event, then the outcome
 *       event, as {@code recordedEvents}; the deadlines the lifecycle opened and the commands
 *       scheduled, as {@code scheduled} (017). It is empty when there is no event to hand on, no
 *       command and nothing scheduled, unless the outbox observes recorded events and some were
 *       recorded.
 *   <li>{@code IN_TRANSACTION}: {@code store(batch)} before {@code commit()}, inside the
 *       transaction, and {@code committed(batch)} once the caller {@linkplain Committed#release()
 *       releases} the result. {@code AFTER_COMMIT}: {@code store(batch)} on release, after the
 *       caller released the instance lock; its failure is logged ({@code saga.outbox.failed}) and
 *       returned, the committed transaction stays as it is.
 *   <li>Any failure discards the recorded events and outbox commands and rolls back if the
 *       transaction is still active: {@link Failed} with the first reported failure, else the
 *       thrown one, or {@link Conflicted} for a {@link SagaConcurrencyConflictException} from the
 *       write or the commit.
 * </ol>
 *
 * <h2>Scopes</h2>
 *
 * <ul>
 *   <li>{@link #forInstance}: locks and writes one existing saga instance.
 *   <li>{@link #forNewInstance}: writes an instance the lifecycle just created; nothing to lock
 *       yet.
 *   <li>{@link #standalone}: no instance; a command handled outside any unit of work.
 * </ul>
 *
 * <h2>Recorder</h2>
 *
 * <p>As the thread's recorder it accepts domain events (the work validates them and may run them
 * itself), command identities and failure reports: the command gateway reports every handler,
 * loader, saver, resolver or provider failure before the exception leaves {@code send}, so the unit
 * of work fails even if the step or handler that called {@code send} catches the exception (002
 * FR-012).
 *
 * <p>Not thread-safe: confined to the thread that runs it. One instance runs one work, once.
 */
final class UnitOfWork implements BoundUnitOfWork {

  /** Collaborators shared by every unit of work of one engine. */
  record Collaborators(
      EngineSettings settings,
      CommandGateway gateway,
      Diagnostics diagnostics,
      SagaInstrumentation instrumentation) {}

  /**
   * What runs inside one unit of work.
   *
   * @param <T> what the work returns when it ran
   * @param <R> what the work reports when its {@linkplain #admit(Optional) admission} skips it
   */
  interface Work<T, R> {

    /**
     * Instance scope only: whether to run against {@code locked} (empty if the instance does not
     * exist). A pure check: it never writes and changes no state of the work. {@link
     * Admission#skip(Object)} rolls back and returns its reason as {@link Skipped}.
     */
    default Admission<R> admit(Optional<SagaInstance> locked) {
      return Admission.proceed();
    }

    /**
     * Runs the work: user code and lifecycle transitions. May throw; any failure fails the unit of
     * work.
     */
    T perform(WorkContext context);

    /**
     * A domain event was recorded while the work ran. The default rejects a {@code null} or
     * non-{@code @DomainEvent} event without failing the unit of work; a work may report the
     * rejection, track the event, or {@linkplain Recording#ranHere() run it itself}.
     */
    default void recorded(Recording recording, WorkContext context) {
      Object event = recording.event();
      if (event == null || !event.getClass().isAnnotationPresent(DomainEvent.class)) {
        throw new IllegalArgumentException("Not a @DomainEvent: " + event);
      }
    }

    /**
     * Whether handling of {@code id} already committed, so its handler must not run again (005
     * FR-004).
     */
    default boolean alreadyHandled(CommandId id) {
      return false;
    }

    /** {@code id}'s handler returned in this unit of work. */
    default void commandHandled(CommandId id) {}
  }

  /** One recorded event and whether the work ran it in this unit of work. */
  static final class Recording {

    private final Object event;
    private boolean ranHere;

    Recording(Object event) {
      this.event = event;
    }

    Object event() {
      return event;
    }

    /**
     * Marks the event as run by the work itself: it is then not handed to the outbox as an event to
     * deliver.
     */
    void ranHere() {
      ranHere = true;
    }
  }

  /**
   * How the unit of work ended.
   *
   * @param <T> what the work returns when it ran
   * @param <R> what the work reports when its admission skips it
   */
  sealed interface Result<T, R> {}

  /**
   * Committed. {@link #release()} runs the outbox step that follows the lock release and returns
   * its failures.
   */
  record Committed<T, R>(T value, String unitOfWorkId, Supplier<List<Throwable>> afterRelease)
      implements Result<T, R> {

    List<Throwable> release() {
      return afterRelease.get();
    }
  }

  /**
   * The work's admission skipped the locked instance; the transaction was rolled back and nothing
   * ran.
   */
  record Skipped<T, R>(R reason) implements Result<T, R> {}

  /** Rolled back after {@code cause}, the first reported failure, else the thrown one. */
  record Failed<T, R>(Throwable cause, String unitOfWorkId) implements Result<T, R> {}

  /** Rolled back after the write or the commit lost a concurrency conflict. */
  record Conflicted<T, R>(SagaConcurrencyConflictException conflict, String unitOfWorkId)
      implements Result<T, R> {}

  private enum Scope {
    INSTANCE,
    NEW_INSTANCE,
    STANDALONE
  }

  private final Scope scope;
  private final EngineSettings settings;
  private final Diagnostics diagnostics;
  private final CommandGateway gateway;
  private final SagaInstrumentation instrumentation;
  private final SagaModel saga;
  private final String sagaId;
  private final String unitOfWorkId;
  private final Correlation correlation;
  private final EngineSagaLifecycle lifecycle;
  private final CommandIds commandIds;
  private final List<Recording> recorded = new ArrayList<>();
  private final List<OutgoingCommand> outboxCommands = new ArrayList<>();
  private final List<ScheduledMessage> scheduled = new ArrayList<>();
  private final List<String> toCancel = new ArrayList<>();
  private List<String> cancelledDeadlines = List.of();
  private int scheduledCommands;
  private Work<?, ?> work;
  private WorkContext context;
  private Optional<OutgoingMessages> batch = Optional.empty();
  private Optional<SagaOutcome> outcome = Optional.empty();
  private boolean writing;
  private Throwable firstFailure;

  private UnitOfWork(
      Scope scope,
      EngineSettings settings,
      Diagnostics diagnostics,
      CommandGateway gateway,
      SagaInstrumentation instrumentation,
      SagaModel saga,
      String sagaId,
      String unitOfWorkId,
      Correlation correlation,
      EngineSagaLifecycle lifecycle) {
    this.scope = scope;
    this.instrumentation = instrumentation;
    this.settings = settings;
    this.diagnostics = diagnostics;
    this.gateway = gateway;
    this.saga = saga;
    this.sagaId = sagaId;
    this.unitOfWorkId = unitOfWorkId;
    this.correlation = correlation;
    this.lifecycle = lifecycle;
    this.commandIds = sagaId == null ? null : new CommandIds(sagaId);
  }

  /**
   * A unit of work that locks and writes the existing instance {@code sagaId} of {@code saga}.
   *
   * @param about the correlation of the call; if it names a unit of work id, the unit of work keeps
   *     it (a write that follows a rolled-back unit of work reports under its id), else a fresh id
   *     is drawn
   */
  static UnitOfWork forInstance(
      Collaborators with, SagaModel saga, String sagaId, Correlation about) {
    EngineSettings settings = with.settings();
    String id =
        about.unitOfWorkId() != null ? about.unitOfWorkId() : settings.sources().newUnitOfWorkId();
    return new UnitOfWork(
        Scope.INSTANCE,
        settings,
        with.diagnostics(),
        with.gateway(),
        with.instrumentation(),
        saga,
        sagaId,
        id,
        about.instance(sagaId).inUnitOfWork(id),
        new EngineSagaLifecycle(saga, settings.storage(), settings.sources()));
  }

  /**
   * A unit of work that writes the instance {@code lifecycle} just {@linkplain
   * EngineSagaLifecycle#start started}; it locks nothing and draws no unit of work id.
   */
  static UnitOfWork forNewInstance(
      Collaborators with, SagaModel saga, EngineSagaLifecycle lifecycle, String sagaId) {
    return new UnitOfWork(
        Scope.NEW_INSTANCE,
        with.settings(),
        with.diagnostics(),
        with.gateway(),
        with.instrumentation(),
        saga,
        sagaId,
        null,
        new Correlation(null, saga.typeName(), sagaId, null),
        lifecycle);
  }

  /** A unit of work outside any saga instance, with a fresh unit of work id. */
  static UnitOfWork standalone(EngineSettings settings, Diagnostics diagnostics) {
    String id = settings.sources().newUnitOfWorkId();
    return new UnitOfWork(
        Scope.STANDALONE,
        settings,
        diagnostics,
        null,
        null,
        null,
        null,
        id,
        new Correlation(null, null, null, id),
        null);
  }

  /**
   * Rethrows {@code failure} unchanged if it is an {@link Error}; returns it for the caller to
   * throw if it is a {@link RuntimeException}; wraps any other {@code Throwable} once in an {@link
   * UndeclaredThrowableException}.
   */
  static RuntimeException propagate(Throwable failure) {
    if (failure instanceof Error error) {
      throw error;
    }
    if (failure instanceof RuntimeException runtime) {
      return runtime;
    }
    return new UndeclaredThrowableException(failure);
  }

  /** This unit of work's id; {@code null} for {@link #forNewInstance}. */
  String unitOfWorkId() {
    return unitOfWorkId;
  }

  /**
   * The correlation of this unit of work: saga type, instance and unit of work id as far as known.
   */
  Correlation correlation() {
    return correlation;
  }

  /**
   * Runs {@code toRun} in this unit of work.
   *
   * @throws RuntimeException if {@code begin()} or the locked read fails; nothing was recorded
   * @throws IllegalStateException if this unit of work already ran
   */
  <T, R> Result<T, R> run(Work<T, R> toRun) {
    if (work != null) {
      throw new IllegalStateException("Unit of work " + unitOfWorkId + " already ran");
    }
    work = toRun;
    context =
        new WorkContext(
            this,
            settings,
            gateway,
            saga,
            sagaId,
            unitOfWorkId,
            correlation,
            lifecycle,
            commandIds);
    LocalTransaction transaction = settings.transactions().begin();
    ReentrancyGuard.enter();
    try {
      if (scope == Scope.INSTANCE) {
        Optional<SagaInstance> locked = lockOrRollBack(transaction);
        if (toRun.admit(locked) instanceof Admission.Skip<R> skip) {
          transaction.rollback();
          return new Skipped<>(skip.reason());
        }
        lifecycle.load(
            locked.orElseThrow(
                () ->
                    new IllegalStateException(
                        "Work "
                            + toRun.getClass().getSimpleName()
                            + " admitted the missing instance "
                            + sagaId)));
      }
      T value;
      try {
        value = toRun.perform(context);
        commit(transaction);
      } catch (SagaConcurrencyConflictException conflict) {
        if (writing) {
          discard(transaction, conflict);
          return new Conflicted<>(conflict, unitOfWorkId);
        }
        return failed(transaction, conflict);
      } catch (RuntimeException | Error failure) {
        return failed(transaction, failure);
      }
      return new Committed<>(value, unitOfWorkId, this::afterRelease);
    } finally {
      ReentrancyGuard.exit();
    }
  }

  @Override
  public void record(Object domainEvent) {
    Recording recording = new Recording(domainEvent);
    work.recorded(recording, context);
    recorded.add(recording);
  }

  @Override
  public void failed(Throwable failure) {
    if (firstFailure == null) {
      firstFailure = failure;
    }
  }

  /**
   * The identity of the next command of {@code commandType} sent in this unit of work (005 FR-001):
   * scoped to the instance and the method the work is {@linkplain WorkContext#running(String)
   * running}, or a fresh {@linkplain CommandId#unscoped(String) unscoped} identity outside an
   * instance.
   */
  @Override
  public CommandId nextCommandId(Class<?> commandType) {
    return commandIds == null
        ? CommandId.unscoped(commandType.getName())
        : commandIds.next(commandType);
  }

  @Override
  public boolean alreadyHandled(CommandId id) {
    return work.alreadyHandled(id);
  }

  @Override
  public void commandHandled(CommandId id) {
    work.commandHandled(id);
  }

  @Override
  public void recordOutboxCommand(OutgoingCommand command) {
    outboxCommands.add(command);
  }

  @Override
  public String nextScheduledMessageId() {
    return unitOfWorkId + ":scheduled:" + (++scheduledCommands);
  }

  @Override
  public void recordScheduled(ScheduledMessage message) {
    scheduled.add(message);
  }

  @Override
  public boolean cancelScheduled(String messageId) {
    if (scheduled.removeIf(message -> message.messageId().equals(messageId))) {
      return true;
    }
    return settings.outbox().cancel(List.of(messageId)).contains(messageId);
  }

  /** Throws the first reported failure, if any. */
  void failIfReported() {
    if (firstFailure != null) {
      throw propagate(firstFailure);
    }
  }

  private Optional<SagaInstance> lockOrRollBack(LocalTransaction transaction) {
    try {
      return settings.storage().findByIdAndLock(sagaId);
    } catch (RuntimeException | Error lockFailure) {
      LocalTransactions.rollBackAfter(settings.transactions(), transaction, lockFailure);
      throw lockFailure;
    }
  }

  private void commit(LocalTransaction transaction) {
    failIfReported();
    if (transaction.isRollbackOnly()) {
      throw new IllegalStateException(
          "Unit of work "
              + unitOfWorkId
              + (sagaId == null ? "" : " of saga instance " + sagaId)
              + " was marked rollback-only");
    }
    if (lifecycle != null) {
      PendingWrite write = lifecycle.drain(sagaId);
      outcome = settings.outcomeEvents() ? write.outcome() : Optional.empty();
      scheduled.addAll(write.scheduled());
      toCancel.addAll(write.cancelled().keySet());
      cancelledDeadlines = write.cancelled().values().stream().toList();
      if (!toCancel.isEmpty()) {
        settings.outbox().cancel(List.copyOf(toCancel));
      }
      writing = true;
      settings.storage().save(write.instance(), write.entries());
      writing = false;
    }
    batch = buffered();
    if (settings.outboxTiming() == Outbox.Timing.IN_TRANSACTION) {
      batch.ifPresent(settings.outbox()::store);
    }
    writing = true;
    transaction.commit();
    countDeadlines();
  }

  /**
   * {@code saga_forge.deadlines} with {@code started} and {@code cancelled} for a committed write
   * (017).
   */
  private void countDeadlines() {
    if (instrumentation == null || saga == null) {
      return;
    }
    scheduled.stream()
        .filter(message -> message.payload() instanceof DeadlineReached)
        .forEach(
            message ->
                countDeadline(((DeadlineReached) message.payload()).deadlineName(), "started"));
    cancelledDeadlines.forEach(name -> countDeadline(name, "cancelled"));
  }

  private void countDeadline(String name, String event) {
    instrumentation.count(
        SagaInstrumentation.DEADLINES_COUNTER,
        Attributes.builder()
            .put(SagaInstrumentation.SAGA_TYPE, saga.typeName())
            .put(SagaInstrumentation.DEADLINE_NAME, name)
            .put(SagaInstrumentation.DEADLINE_EVENT, event)
            .build());
  }

  private <T, R> Result<T, R> failed(LocalTransaction transaction, Throwable thrown) {
    Throwable cause = firstFailure != null ? firstFailure : thrown;
    discard(transaction, cause);
    return new Failed<>(cause, unitOfWorkId);
  }

  /**
   * Drops the buffer and rolls back if the transaction is still active; pending entries die with
   * it.
   */
  private void discard(LocalTransaction transaction, Throwable cause) {
    recorded.clear();
    outboxCommands.clear();
    scheduled.clear();
    toCancel.clear();
    batch = Optional.empty();
    outcome = Optional.empty();
    LocalTransactions.rollBackAfter(settings.transactions(), transaction, cause);
  }

  /**
   * After the caller released the instance lock. {@code IN_TRANSACTION}: {@code committed(batch)},
   * a failure logged and ignored. {@code AFTER_COMMIT}: {@code store(batch)}; a failure leaves the
   * committed transaction untouched and is logged and returned, with its suppressed exceptions (004
   * FR-031).
   */
  private List<Throwable> afterRelease() {
    if (batch.isEmpty()) {
      return List.of();
    }
    if (settings.outboxTiming() == Outbox.Timing.IN_TRANSACTION) {
      try {
        settings.outbox().committed(batch.get());
      } catch (RuntimeException failure) {
        diagnostics.outboxCommittedFailed(correlation, failure);
      }
      return List.of();
    }
    if (settings.outboxTiming() != Outbox.Timing.AFTER_COMMIT) {
      return List.of();
    }
    try {
      settings.outbox().store(batch.get());
      return List.of();
    } catch (RuntimeException outboxFailure) {
      diagnostics.outboxFailed(correlation, batch.get().events().size(), outboxFailure);
      List<Throwable> failures = new ArrayList<>();
      failures.add(outboxFailure);
      failures.addAll(List.of(outboxFailure.getSuppressed()));
      return failures;
    }
  }

  /**
   * Every recorded event the work did not run, in recording order, then the outcome event; every
   * outbox command, in send order; and every recorded event at all, then the outcome event (008
   * {@code recordedEvents}). Empty if there is neither an event to hand on nor a command, unless
   * the outbox observes recorded events and some were recorded.
   */
  private Optional<OutgoingMessages> buffered() {
    List<Object> events = new ArrayList<>();
    List<Object> recordedEvents = new ArrayList<>();
    recorded.forEach(
        entry -> {
          if (!entry.ranHere) {
            events.add(entry.event);
          }
          recordedEvents.add(entry.event);
        });
    outcome.ifPresent(events::add);
    outcome.ifPresent(recordedEvents::add);
    boolean nothingToHandOn = events.isEmpty() && outboxCommands.isEmpty() && scheduled.isEmpty();
    if (nothingToHandOn
        && (recordedEvents.isEmpty() || !settings.outbox().observesRecordedEvents())) {
      return Optional.empty();
    }
    return Optional.of(
        new OutgoingMessages(
            unitOfWorkId,
            saga == null ? null : saga.typeName(),
            sagaId,
            events,
            List.copyOf(outboxCommands),
            recordedEvents,
            List.copyOf(scheduled)));
  }
}
