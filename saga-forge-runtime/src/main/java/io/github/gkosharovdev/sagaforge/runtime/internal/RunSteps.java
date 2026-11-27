package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.deadline.OpenDeadline;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.SagaModel.Step;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Recording;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs the steps one event triggers for one saga instance: one attempt of the {@link AttemptLoop}
 * (002 contracts/saga-unit-of-work.md made concrete by contracts/contract-deltas.md; 005
 * contracts/attempt-loop.md).
 *
 * <h2>Admission</h2>
 *
 * <p>A missing instance is {@code UNMATCHED}; a {@code COMPENSATING} one returns {@link
 * Compensating}, so the loop resumes its compensations (006 research §10, checked before the
 * duplicate check); one whose triggering (non-start) step already completed is {@code DUPLICATE}
 * (005 FR-006), a terminal one {@code UNEXPECTED}, a still-{@code CREATED} one reached by a
 * non-start step {@code NOT_HANDLED}. On the first attempt of a handling call a retry claimed for
 * the trigger step by an earlier call returns {@link Resume}, so the loop claims the next attempt
 * first (005 research §9). None of these runs anything.
 *
 * <p>A {@code DeadlineReached} (017) is admitted only if the instance is {@code RUNNING} and holds
 * the deadline open under the delivered id; otherwise it is {@code IGNORED} ({@code
 * saga.deadline.ignored}, reason {@code NOT_RUNNING}, {@code NOT_OPEN} or {@code REPLACED}),
 * whatever the status, so a {@code COMPENSATING} instance is not resumed by it. The timeout step
 * runs even if it completed before for an earlier deadline of the same name.
 *
 * <h2>Deadlines (017)</h2>
 *
 * <p>The timeout step's deadline is expired before it runs; after each completed step the {@link
 * DeadlinePlan} cancels the deadlines the step cancels and starts the ones it declares. A timeout
 * step that is {@code @StartCompensation} starts compensation with a {@code DEADLINE_EXPIRED}
 * trigger.
 *
 * <h2>Steps</h2>
 *
 * <p>A fresh saga object with its fields injected runs the triggered step, then every queued event
 * in recording order. After each step {@code recordStepCompleted}; at the first {@code @EndSaga}
 * step {@code complete} and stop; at the first {@code @StartCompensation} step {@code
 * startCompensation} and stop (006 FR-002, the committed result then carries {@code
 * compensationStarted}); at an empty queue {@code advance}. Its success clears a retry claimed for
 * its trigger step.
 *
 * <h2>Recording</h2>
 *
 * <p>Each event adds {@code (@DomainEvent.aggregate, @AggregateIdentifier value)} to the running
 * step's touched aggregates. It is queued if it triggers a non-start step of this saga type and
 * carries this instance's association value for that step's property; otherwise the unit of work
 * hands it to the outbox (004 FR-008, FR-017). A queued event whose step already completed is not
 * run and goes to the outbox like any other.
 *
 * <h2>Results</h2>
 *
 * <p>A failure ends the run: {@link Failed} names the step running or last run and the first
 * reported cause, and {@code saga.uow.rolledBack} is logged; the loop classifies it. A write-time
 * conflict logs {@code saga.uow.conflict} and is {@code NOT_HANDLED} with the unit of work's id
 * (004 FR-026). A commit logs {@code saga.uow.committed}.
 *
 * <p>Not thread-safe: confined to the thread that called {@code receive}. One instance per attempt.
 */
final class RunSteps implements UnitOfWork.Work<Void, RunSteps.AttemptResult> {

  /** What triggered the steps, for which instance. */
  record Trigger(SagaModel saga, Step step, Object event, String sagaId, boolean started) {}

  /** How one attempt ended, for the {@link AttemptLoop} (005 contracts/attempt-loop.md). */
  sealed interface AttemptResult {}

  /**
   * Nothing more to do: the attempt ran nothing, or lost a write-time conflict. The transaction is
   * closed.
   */
  record Finished(SagaHandlingResult result) implements AttemptResult {}

  /**
   * The attempt committed. {@link #store()} runs the outbox step after the lock release and returns
   * the result; the caller releases the instance lock first.
   */
  record Committed(
      Supplier<SagaHandlingResult> afterRelease, boolean compensationStarted, String unitOfWorkId)
      implements AttemptResult {

    SagaHandlingResult store() {
      return afterRelease.get();
    }
  }

  /**
   * The attempt rolled back: {@code step} was running or last run, {@code cause} is the failure to
   * classify.
   */
  record Failed(String step, Throwable cause, Correlation correlation, int attempt)
      implements AttemptResult {}

  /**
   * The first attempt of a handling call found a retry claimed for its trigger step by an earlier
   * call; it rolled back and ran nothing, so the loop can claim the next attempt first (005
   * research §9).
   */
  record Resume(PendingRetry retry, Correlation correlation) implements AttemptResult {}

  /**
   * The locked instance is {@code COMPENSATING}: the event's step did not run and the attempt
   * rolled back, so the loop can resume the remaining compensations (006 research §10).
   */
  record Compensating(Correlation correlation) implements AttemptResult {}

  /** An event waiting to run a step of this instance. */
  private record Queued(Step step, Recording recording) {}

  private final Trigger trigger;
  private final Collaborators with;
  private final int attempt;
  private final boolean firstOfCall;
  private final Correlation correlation;
  private final Deque<Queued> queue = new ArrayDeque<>();
  private final List<String> stepsRun = new ArrayList<>();
  private final Set<CommandId> committedCommands = new HashSet<>();
  private final Set<String> completedSteps = new HashSet<>();
  private List<AggregateReference> touched = new ArrayList<>();
  private List<CommandId> handledInStep = new ArrayList<>();
  private Map<String, String> associations = Map.of();
  private String currentStep;
  private boolean ended;
  private boolean compensationStarted;
  private CompensationTrigger lifecycleTrigger;
  private DeadlinePlan deadlines;

  private RunSteps(
      Trigger trigger,
      Collaborators with,
      int attempt,
      boolean firstOfCall,
      Correlation correlation) {
    this.trigger = trigger;
    this.with = with;
    this.attempt = attempt;
    this.firstOfCall = firstOfCall;
    this.correlation = correlation;
    this.currentStep = trigger.step().name();
  }

  /**
   * Runs one attempt in its own unit of work to its commit, to its rollback, or to a result that
   * ran nothing. Writes no {@code FAILED} and releases no lock: both are the {@link AttemptLoop}'s.
   *
   * @param attempt the attempt, counting the first as 1; recorded on the steps it completes
   * @param firstOfCall whether this is the first attempt of the handling call, which resumes a
   *     retry claimed by an earlier call instead of running
   * @throws RuntimeException if the locked read fails; nothing was recorded
   */
  static AttemptResult attempt(
      Collaborators with, Trigger trigger, int attempt, boolean firstOfCall) {
    Correlation about = Correlation.of(trigger.event()).forSaga(trigger.saga().typeName());
    UnitOfWork unitOfWork = UnitOfWork.forInstance(with, trigger.saga(), trigger.sagaId(), about);
    RunSteps work = new RunSteps(trigger, with, attempt, firstOfCall, unitOfWork.correlation());
    return switch (unitOfWork.run(work)) {
      case UnitOfWork.Skipped<Void, AttemptResult> skipped -> skipped.reason();
      case UnitOfWork.Failed<Void, AttemptResult> failed -> {
        with.diagnostics().unitOfWorkRolledBack(work.correlation, work.currentStep, failed.cause());
        yield new Failed(work.currentStep, failed.cause(), work.correlation, attempt);
      }
      case UnitOfWork.Conflicted<Void, AttemptResult> conflicted -> {
        with.diagnostics().unitOfWorkConflict(work.correlation);
        yield new Finished(
            new SagaHandlingResult(
                trigger.saga().typeName(),
                trigger.sagaId(),
                HandlingOutcome.NOT_HANDLED,
                trigger.started(),
                null,
                null,
                conflicted.unitOfWorkId(),
                List.of()));
      }
      case UnitOfWork.Committed<Void, AttemptResult> committed -> {
        with.diagnostics().unitOfWorkCommitted(work.correlation, work.outcome(), work.stepsRun);
        if (work.compensationStarted) {
          with.diagnostics().compensationStarted(work.correlation, work.lifecycleTrigger);
        }
        yield new Committed(
            () -> work.committedResult(committed.release(), committed.unitOfWorkId()),
            work.compensationStarted,
            committed.unitOfWorkId());
      }
    };
  }

  /** Whether {@code stepName} completed for {@code instance}. */
  static boolean isCompleted(SagaInstance instance, String stepName) {
    StepRecord step = instance.steps().get(stepName);
    return step != null && step.status() == StepStatus.COMPLETED;
  }

  @Override
  public Admission<AttemptResult> admit(Optional<SagaInstance> locked) {
    if (locked.isEmpty()) {
      return Admission.skip(
          new Finished(
              trigger.event() instanceof DeadlineReached reached
                  ? ignoredDeadline(reached, "NOT_RUNNING")
                  : unmatched()));
    }
    SagaInstance instance = locked.get();
    if (trigger.event() instanceof DeadlineReached reached) {
      String ignored = ignoredReason(instance, reached);
      if (ignored != null) {
        return Admission.skip(new Finished(ignoredDeadline(reached, ignored)));
      }
    } else if (instance.status() == SagaStatus.COMPENSATING) {
      return Admission.skip(new Compensating(correlation.inUnitOfWork(null)));
    }
    if (!trigger.step().start()
        && !trigger.step().expiresDeadline()
        && isCompleted(instance, trigger.step().name())) {
      return Admission.skip(new Finished(duplicate()));
    }
    if (EngineSagaLifecycle.isTerminal(instance.status())) {
      return Admission.skip(new Finished(unexpected()));
    }
    if (instance.status() == SagaStatus.CREATED && !trigger.step().start()) {
      return Admission.skip(new Finished(notStarted()));
    }
    PendingRetry claimed = instance.pendingRetry();
    if (firstOfCall && claimed != null && claimed.triggerStep().equals(trigger.step().name())) {
      return Admission.skip(new Resume(claimed, correlation));
    }
    return Admission.proceed();
  }

  /**
   * Why {@code reached} must not run a step of {@code instance}; {@code null} if it may (017
   * FR-014).
   */
  private static String ignoredReason(SagaInstance instance, DeadlineReached reached) {
    if (instance.status() != SagaStatus.RUNNING) {
      return "NOT_RUNNING";
    }
    OpenDeadline open = instance.openDeadlines().get(reached.deadlineName());
    if (open == null) {
      return "NOT_OPEN";
    }
    return open.deadlineId().equals(reached.deadlineId()) ? null : "REPLACED";
  }

  @Override
  public Void perform(WorkContext context) {
    rememberCommitted(context.lifecycle().current());
    deadlines =
        new DeadlinePlan(
            trigger.saga(),
            trigger.sagaId(),
            context.unitOfWorkId(),
            context.settings().sources()::now,
            () ->
                new EngineSagaContext(
                    trigger.sagaId(),
                    context.settings().contextStore(),
                    context.settings().sources().clock()));
    if (trigger.step().start()
        && !trigger.started()
        && context.lifecycle().current().status() == SagaStatus.CREATED) {
      // 015 FR-028: a redelivered start event adopted a stranded start (EventRouter).
      context.lifecycle().recordStartRecovered(trigger.sagaId());
    }
    if (trigger.step().expiresDeadline()) {
      deadlines.expire(trigger.step().deadlineName(), context.lifecycle());
    }
    Object saga = context.newSagaObject();
    queue.add(new Queued(trigger.step(), new Recording(trigger.event())));
    context.recording(() -> runQueued(saga, context));
    context.failIfReported();
    context.lifecycle().clearPendingRetry(trigger.step().name());
    return null;
  }

  /**
   * What this attempt needs of the locked instance: its associations, committed commands and
   * completed steps.
   */
  private void rememberCommitted(SagaInstance instance) {
    associations = instance.associations();
    instance.steps().values().stream()
        .map(StepRecord::handledCommands)
        .forEach(committedCommands::addAll);
    instance
        .steps()
        .forEach(
            (name, step) -> {
              if (step.status() == StepStatus.COMPLETED) {
                completedSteps.add(name);
              }
            });
  }

  @Override
  public void recorded(Recording recording, WorkContext context) {
    Object domainEvent = recording.event();
    touched.add(touchedAggregateOf(domainEvent, context));
    inProcessStepFor(domainEvent).ifPresent(step -> queue.add(new Queued(step, recording)));
  }

  @Override
  public boolean alreadyHandled(CommandId id) {
    return committedCommands.contains(id);
  }

  @Override
  public void commandHandled(CommandId id) {
    handledInStep.add(id);
  }

  private void runQueued(Object saga, WorkContext context) {
    EngineSagaLifecycle lifecycle = context.lifecycle();
    while (!queue.isEmpty()) {
      Queued next = queue.poll();
      boolean timeoutStep =
          next.step().expiresDeadline() && next.recording().event() == trigger.event();
      if (completedSteps.contains(next.step().name()) && !timeoutStep) {
        // A repeated event for a step already completed: not run, stored in the outbox like any
        // non-queued event.
        with.diagnostics().eventDuplicate(correlation, next.step().name());
        continue;
      }
      runStep(saga, next, context);
      if (next.step().end()) {
        lifecycle.complete(trigger.sagaId(), next.step().name());
        ended = true;
        return;
      }
      if (next.step().startsCompensation()) {
        lifecycleTrigger =
            next.step().expiresDeadline()
                ? CompensationTrigger.deadlineExpired(
                    next.step().name(),
                    DeadlineReached.class.getName(),
                    next.step().deadlineName(),
                    context.settings().sources().now())
                : CompensationTrigger.failureOutcome(
                    next.step().name(),
                    next.recording().event().getClass().getName(),
                    context.settings().sources().now());
        lifecycle.startCompensation(trigger.sagaId(), lifecycleTrigger);
        compensationStarted = true;
        return;
      }
    }
    lifecycle.advance(trigger.sagaId());
  }

  private void runStep(Object saga, Queued next, WorkContext context) {
    next.recording().ranHere();
    currentStep = next.step().name();
    stepsRun.add(currentStep);
    touched = new ArrayList<>();
    handledInStep = new ArrayList<>();
    boolean timeoutRun =
        next.step().expiresDeadline() && next.recording().event() instanceof DeadlineReached;
    context.running(
        currentStep, timeoutRun ? ((DeadlineReached) next.recording().event()).deadlineId() : null);
    try (SagaInstrumentation.Traced span = with.instrumentation().start(SagaInstrumentation.STEP)) {
      span.set(SagaInstrumentation.SAGA_TYPE, trigger.saga().typeName())
          .set(SagaInstrumentation.STEP_NAME, currentStep)
          .set(SagaInstrumentation.SAGA_ID, trigger.sagaId());
      try {
        trigger.saga().run(next.step(), saga, next.recording().event());
        context.failIfReported();
      } catch (RuntimeException | Error failure) {
        span.fail(failure);
        throw failure;
      }
    }
    context
        .lifecycle()
        .recordStepCompleted(trigger.sagaId(), currentStep, touched, attempt, handledInStep);
    completedSteps.add(currentStep);
    deadlines.stepCompleted(currentStep, context.lifecycle());
  }

  private AggregateReference touchedAggregateOf(Object domainEvent, WorkContext context) {
    try {
      return with.eventAggregates().touchedBy(domainEvent);
    } catch (RuntimeException invalid) {
      context.reportFailure(invalid);
      throw invalid;
    }
  }

  private Optional<Step> inProcessStepFor(Object domainEvent) {
    return trigger
        .saga()
        .stepFor(domainEvent.getClass())
        .filter(step -> !step.start())
        .filter(step -> correlates(step, domainEvent));
  }

  private boolean correlates(Step step, Object domainEvent) {
    String expected = associations.get(step.associationProperty());
    return expected != null
        && trigger.saga().associationValue(step, domainEvent).filter(expected::equals).isPresent();
  }

  private SagaHandlingResult unmatched() {
    with.diagnostics().eventUnmatched(correlation.instance(null).inUnitOfWork(null), currentStep);
    return resultWithoutUnitOfWork(HandlingOutcome.UNMATCHED, null);
  }

  private SagaHandlingResult unexpected() {
    with.diagnostics().eventUnexpected(correlation.inUnitOfWork(null), currentStep);
    return resultWithoutUnitOfWork(HandlingOutcome.UNEXPECTED, trigger.sagaId());
  }

  /**
   * The triggering step already completed for the instance (005 FR-006): nothing runs, nothing is
   * written.
   */
  private SagaHandlingResult duplicate() {
    with.diagnostics().eventDuplicate(correlation.inUnitOfWork(null), currentStep);
    return resultWithoutUnitOfWork(HandlingOutcome.DUPLICATE, trigger.sagaId());
  }

  private SagaHandlingResult ignoredDeadline(DeadlineReached reached, String reason) {
    with.diagnostics()
        .deadlineIgnored(
            correlation.inUnitOfWork(null), reached.deadlineName(), reached.deadlineId(), reason);
    return resultWithoutUnitOfWork(HandlingOutcome.IGNORED, trigger.sagaId());
  }

  private SagaHandlingResult notStarted() {
    with.diagnostics().unitOfWorkNotStarted(correlation.inUnitOfWork(null));
    return resultWithoutUnitOfWork(HandlingOutcome.NOT_HANDLED, trigger.sagaId());
  }

  private SagaHandlingResult resultWithoutUnitOfWork(HandlingOutcome outcome, String sagaId) {
    return new SagaHandlingResult(
        trigger.saga().typeName(), sagaId, outcome, trigger.started(), null, null, null, List.of());
  }

  private HandlingOutcome outcome() {
    if (compensationStarted) {
      return HandlingOutcome.COMPENSATED;
    }
    return ended ? HandlingOutcome.COMPLETED : HandlingOutcome.WAITING;
  }

  private SagaHandlingResult committedResult(List<Throwable> outboxFailures, String unitOfWorkId) {
    return new SagaHandlingResult(
        trigger.saga().typeName(),
        trigger.sagaId(),
        outcome(),
        trigger.started(),
        ended ? currentStep : null,
        null,
        unitOfWorkId,
        outboxFailures);
  }
}
