package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.lifecycle.DuplicateSagaInstanceException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.InvalidSagaTransitionException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaLifecycle;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.PendingRetry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepCompensation;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The production {@link SagaLifecycle}: applies the 001/002 transition rules to a working copy of one instance
 * and collects the lifecycle entries they produce, without writing anything (contracts/contract-deltas.md).
 *
 * <p>One lifecycle serves one write: the creation write ({@link #start}), a unit of work ({@link #load} from the
 * locked read, then steps), or the {@code FAILED} write. The caller hands {@link #drain(String)}'s instance and
 * entries to {@code StorageProvider.save(instance, entries)}. Entries follow data-model.md "Lifecycle entry
 * production": {@code start} → {@code CREATED} ({@code occurredAt = createdAt}), {@code recordStepCompleted} →
 * {@code STEP_COMPLETED}, {@code advance} → none, {@code complete} → {@code COMPLETED}, {@code fail} →
 * {@code FAILED}, {@link #attemptFailed} → {@code ATTEMPT_FAILED} (005), {@link #startCompensation} →
 * {@code COMPENSATION_STARTED} (and {@code COMPENSATED} when nothing is left to run), {@link #recordCompensated} →
 * {@code STEP_COMPENSATED} (and {@code COMPENSATED} after the last), {@link #recordCompensationFailed} →
 * {@code COMPENSATION_FAILED} then {@code REQUIRES_INTERVENTION} (006); each entry's {@code occurredAt} is the instant of its transition, which is also the instance's
 * {@code lastModifiedAt}. {@code SagaInstance.state} is always empty (G-ENG-06). A pending retry is kept across
 * transitions until {@link #clearPendingRetry}, a terminal transition or entering compensation drops it (005 research
 * §9). Each completed step gets the next {@code completionSequence} of its instance (006 research §5).</p>
 *
 * <p>{@code start} checks for a duplicate against storage: an active instance of the same saga type with the same
 * association value raises {@link DuplicateSagaInstanceException} (001 FR-006). Every other transition on an
 * instance other than the working copy raises {@link InvalidSagaTransitionException#notFound}, and on a terminal
 * working copy {@link InvalidSagaTransitionException#terminal}.</p>
 *
 * <p>Not thread-safe: confined to the thread of its write.</p>
 */
final class EngineSagaLifecycle implements SagaLifecycle {

    /**
     * What one write persists: the instance and the entries produced since the last drain, in transition order.
     */
    record PendingWrite(SagaInstance instance, List<SagaLifecycleEntry> entries) {

        PendingWrite {
            entries = List.copyOf(entries);
        }
    }

    /** How a transition ends the instance, if it does. */
    private record Ending(String outcomeStep, SagaFailure failure) {

        static final Ending NONE = new Ending(null, null);
    }

    private final SagaModel saga;
    private final StorageProvider storage;
    private final TimeAndIdentity sources;
    private final List<SagaLifecycleEntry> pending = new ArrayList<>();
    private SagaInstance workingCopy;

    /**
     * @param saga    the saga type this lifecycle's instance belongs to
     * @param storage read for the duplicate-start check only
     * @param sources the clock and the saga-id supplier
     */
    EngineSagaLifecycle(SagaModel saga, StorageProvider storage, TimeAndIdentity sources) {
        this.saga = requireNonNull(saga, "saga");
        this.storage = requireNonNull(storage, "storage");
        this.sources = requireNonNull(sources, "sources");
    }

    /**
     * Seeds the working copy from the instance a unit of work or {@code FAILED} write has just locked.
     *
     * @throws IllegalArgumentException if {@code instance} is {@code null} or of another saga type
     * @throws IllegalStateException    if this lifecycle already holds an instance
     */
    void load(SagaInstance instance) {
        requireNonNull(instance, "instance");
        if (!instance.sagaType().equals(saga.typeName())) {
            throw new IllegalArgumentException("Instance " + instance.sagaId() + " is a " + instance.sagaType()
                    + ", not a " + saga.typeName());
        }
        requireEmpty();
        workingCopy = instance;
    }

    @Override
    public SagaInstance start(Class<?> sagaType, String associationProperty, String associationValue) {
        requireNonNull(sagaType, "sagaType");
        requireNonNull(associationProperty, "associationProperty");
        requireNonNull(associationValue, "associationValue");
        if (sagaType != saga.type()) {
            throw new IllegalArgumentException("This lifecycle serves " + saga.typeName() + ", not "
                    + sagaType.getName());
        }
        requireEmpty();
        rejectDuplicate(associationProperty, associationValue);
        Instant now = sources.now();
        workingCopy = new SagaInstance(sources.newSagaId(), saga.typeName(), Map.of(),
                Map.of(associationProperty, associationValue), SagaStatus.CREATED, now, now, notRunSteps(), null,
                null);
        pending.add(entry(SagaLifecycleEventKind.CREATED, now).build());
        return workingCopy;
    }

    @Override
    public SagaInstance recordStepCompleted(
            String sagaId, String stepName, List<AggregateReference> touchedAggregates) {
        return recordStepCompleted(sagaId, stepName, touchedAggregates, 1, List.of());
    }

    /**
     * {@link #recordStepCompleted(String, String, List)} with the attempt the step completed in and the identities of
     * the commands it had handled (005 FR-004, FR-017).
     */
    SagaInstance recordStepCompleted(String sagaId, String stepName, List<AggregateReference> touchedAggregates,
            int attempts, List<CommandId> handledCommands) {
        requireNonNull(stepName, "stepName");
        requireNonNull(touchedAggregates, "touchedAggregates");
        requireNonNull(handledCommands, "handledCommands");
        SagaInstance current = requireActive(sagaId, "recordStepCompleted");
        if (!current.steps().containsKey(stepName)) {
            throw new IllegalArgumentException("Unknown step '" + stepName + "' for saga instance '" + sagaId
                    + "' of type " + current.sagaType());
        }
        Map<String, StepRecord> steps = new HashMap<>(current.steps());
        steps.put(stepName, StepRecord.completed(touchedAggregates, attempts, handledCommands,
                nextCompletionSequence(current)));
        Instant now = evolve(current.status(), steps, Ending.NONE);
        pending.add(entry(SagaLifecycleEventKind.STEP_COMPLETED, now).step(stepName, touchedAggregates).build());
        return workingCopy;
    }

    @Override
    public SagaInstance advance(String sagaId) {
        SagaInstance current = requireActive(sagaId, "advance");
        evolve(SagaStatus.RUNNING, current.steps(), Ending.NONE);
        return workingCopy;
    }

    @Override
    public SagaInstance complete(String sagaId, String outcomeStep) {
        requireNonNull(outcomeStep, "outcomeStep");
        SagaInstance current = requireActive(sagaId, "complete");
        Instant now = evolve(SagaStatus.COMPLETED, current.steps(), new Ending(outcomeStep, null));
        pending.add(entry(SagaLifecycleEventKind.COMPLETED, now).outcome(outcomeStep).build());
        return workingCopy;
    }

    @Override
    public SagaInstance fail(String sagaId, SagaFailure failure) {
        requireNonNull(failure, "failure");
        SagaInstance current = requireActive(sagaId, "fail");
        Instant now = evolve(SagaStatus.FAILED, current.steps(), new Ending(null, failure));
        pending.add(entry(SagaLifecycleEventKind.FAILED, now).failure(failure).build());
        return workingCopy;
    }

    /**
     * Records that an attempt failed transiently and claims the next one: sets {@code retry} as the instance's
     * pending retry, keeps its status, and produces an {@code ATTEMPT_FAILED} entry naming the failed step and
     * carrying {@code retry.lastFailure()} (005 research §9).
     *
     * @throws InvalidSagaTransitionException if {@code sagaId} is not the working copy's or it is terminal
     */
    SagaInstance attemptFailed(String sagaId, PendingRetry retry) {
        requireNonNull(retry, "retry");
        SagaInstance current = requireActive(sagaId, "attemptFailed");
        workingCopy = current.withPendingRetry(retry);
        Instant now = evolve(current.status(), current.steps(), Ending.NONE);
        SagaFailure failure = retry.lastFailure();
        pending.add(entry(SagaLifecycleEventKind.ATTEMPT_FAILED, now).failedStep(failure.stepName())
                .failure(failure).build());
        return workingCopy;
    }

    /**
     * Claims attempt {@code retry.attemptsStarted()} for a redelivery that resumes a retry after a crash or an
     * interrupted wait: sets {@code retry} as the pending retry; produces no entry, as no attempt failed (005
     * research §9).
     *
     * @throws InvalidSagaTransitionException if {@code sagaId} is not the working copy's or it is terminal
     */
    SagaInstance claimAttempt(String sagaId, PendingRetry retry) {
        requireNonNull(retry, "retry");
        SagaInstance current = requireActive(sagaId, "claimAttempt");
        workingCopy = current.withPendingRetry(retry);
        evolve(current.status(), current.steps(), Ending.NONE);
        return workingCopy;
    }

    /**
     * Drops the instance's pending retry if it was claimed for {@code triggerStep}; produces no entry.
     */
    void clearPendingRetry(String triggerStep) {
        requireNonNull(triggerStep, "triggerStep");
        if (workingCopy != null && workingCopy.pendingRetry() != null
                && workingCopy.pendingRetry().triggerStep().equals(triggerStep)) {
            workingCopy = workingCopy.withPendingRetry(null);
        }
    }

    /**
     * Enters {@code COMPENSATING} with {@code trigger} (006 research §5, §6): every completed step except a
     * {@code FAILURE_OUTCOME} trigger's own step gets {@code NOT_ATTEMPTED} if the saga declares a compensation for
     * it, else {@code NOTHING_TO_COMPENSATE}; a pending retry is dropped; produces {@code COMPENSATION_STARTED}. If
     * no step is {@code NOT_ATTEMPTED}, the instance also ends {@code COMPENSATED} ({@code COMPENSATED} entry).
     *
     * @throws InvalidSagaTransitionException if {@code sagaId} is not the working copy's, it is terminal or already
     *                                        compensating
     */
    SagaInstance startCompensation(String sagaId, CompensationTrigger trigger) {
        requireNonNull(trigger, "trigger");
        SagaInstance current = requireActive(sagaId, "startCompensation");
        if (current.status() == SagaStatus.COMPENSATING) {
            throw InvalidSagaTransitionException.terminal(sagaId, "startCompensation", current.status());
        }
        Instant now = trigger.at();
        Map<String, StepRecord> steps = new HashMap<>(current.steps());
        current.steps().forEach((name, step) -> {
            boolean triggerStep = trigger.kind() == CompensationTriggerKind.FAILURE_OUTCOME
                    && trigger.stepName().equals(name);
            if (step.status() == StepStatus.COMPLETED && !triggerStep) {
                steps.put(name, step.withCompensation(saga.compensationFor(name).isPresent()
                        ? StepCompensation.notAttempted()
                        : StepCompensation.nothingToCompensate(now)));
            }
        });
        replace(SagaStatus.COMPENSATING, steps, Ending.NONE, trigger, now);
        pending.add(entry(SagaLifecycleEventKind.COMPENSATION_STARTED, now).trigger(trigger).build());
        endCompensatedIfDone(now);
        return workingCopy;
    }

    /**
     * Marks {@code stepName}'s compensation {@code COMPENSATED} ({@code STEP_COMPENSATED}); after the last
     * {@code NOT_ATTEMPTED} step the instance ends {@code COMPENSATED} ({@code COMPENSATED} entry).
     *
     * @throws InvalidSagaTransitionException if the working copy is not this compensating instance
     * @throws IllegalArgumentException       if the step's compensation is not {@code NOT_ATTEMPTED}
     */
    SagaInstance recordCompensated(String sagaId, String stepName) {
        StepRecord step = requireNotAttempted(sagaId, stepName, "recordCompensated");
        Instant now = sources.now();
        Map<String, StepRecord> steps = new HashMap<>(workingCopy.steps());
        steps.put(stepName, step.withCompensation(StepCompensation.compensated(now)));
        replace(SagaStatus.COMPENSATING, steps, Ending.NONE, workingCopy.compensationTrigger(), now);
        pending.add(entry(SagaLifecycleEventKind.STEP_COMPENSATED, now).failedStep(stepName).build());
        endCompensatedIfDone(now);
        return workingCopy;
    }

    /**
     * Marks {@code stepName}'s compensation {@code FAILED} with {@code failure} and ends the instance
     * {@code REQUIRES_INTERVENTION}; produces {@code COMPENSATION_FAILED} then {@code REQUIRES_INTERVENTION}
     * (006 research §9). Steps not reached stay {@code NOT_ATTEMPTED}.
     *
     * @throws InvalidSagaTransitionException if the working copy is not this compensating instance
     * @throws IllegalArgumentException       if the step's compensation is not {@code NOT_ATTEMPTED}
     */
    SagaInstance recordCompensationFailed(String sagaId, String stepName, SagaFailure failure) {
        requireNonNull(failure, "failure");
        StepRecord step = requireNotAttempted(sagaId, stepName, "recordCompensationFailed");
        Instant now = sources.now();
        Map<String, StepRecord> steps = new HashMap<>(workingCopy.steps());
        steps.put(stepName, step.withCompensation(StepCompensation.failed(failure)));
        replace(SagaStatus.REQUIRES_INTERVENTION, steps, Ending.NONE, workingCopy.compensationTrigger(), now);
        pending.add(entry(SagaLifecycleEventKind.COMPENSATION_FAILED, now).failedStep(stepName).failure(failure)
                .build());
        pending.add(entry(SagaLifecycleEventKind.REQUIRES_INTERVENTION, now).build());
        return workingCopy;
    }

    /**
     * @return the steps of {@code instance} whose compensation is {@code NOT_ATTEMPTED}, most recently completed
     *         first (006 research §5)
     */
    static List<String> remainingCompensations(SagaInstance instance) {
        return instance.steps().entrySet().stream()
                .filter(entry -> entry.getValue().compensation() != null
                        && entry.getValue().compensation().status() == CompensationStatus.NOT_ATTEMPTED)
                .sorted(Comparator.comparingInt(
                        (Map.Entry<String, StepRecord> entry) -> entry.getValue().completionSequence()).reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private void endCompensatedIfDone(Instant now) {
        if (remainingCompensations(workingCopy).isEmpty()) {
            replace(SagaStatus.COMPENSATED, workingCopy.steps(), Ending.NONE, workingCopy.compensationTrigger(), now);
            pending.add(entry(SagaLifecycleEventKind.COMPENSATED, now).build());
        }
    }

    private StepRecord requireNotAttempted(String sagaId, String stepName, String operation) {
        requireNonNull(stepName, "stepName");
        SagaInstance current = requireActive(sagaId, operation);
        if (current.status() != SagaStatus.COMPENSATING) {
            throw InvalidSagaTransitionException.terminal(sagaId, operation, current.status());
        }
        StepRecord step = current.steps().get(stepName);
        if (step == null || step.compensation() == null
                || step.compensation().status() != CompensationStatus.NOT_ATTEMPTED) {
            throw new IllegalArgumentException("Step '" + stepName + "' of saga instance '" + sagaId
                    + "' has no compensation waiting to run");
        }
        return step;
    }

    private static int nextCompletionSequence(SagaInstance instance) {
        return instance.steps().values().stream()
                .filter(step -> step.status() == StepStatus.COMPLETED)
                .mapToInt(StepRecord::completionSequence)
                .max()
                .orElse(0) + 1;
    }

    /**
     * The working copy and the entries produced since the last drain, for one write; clears the entries.
     *
     * @throws InvalidSagaTransitionException if {@code sagaId} is not the working copy's
     */
    PendingWrite drain(String sagaId) {
        requireNonNull(sagaId, "sagaId");
        requireHeld(sagaId, "drain");
        PendingWrite write = new PendingWrite(workingCopy, pending);
        pending.clear();
        return write;
    }

    /** {@code true} for {@code COMPLETED}, {@code FAILED}, {@code COMPENSATED} and {@code REQUIRES_INTERVENTION}. */
    static boolean isTerminal(SagaStatus status) {
        return status == SagaStatus.COMPLETED || status == SagaStatus.FAILED || status == SagaStatus.COMPENSATED
                || status == SagaStatus.REQUIRES_INTERVENTION;
    }

    private void rejectDuplicate(String associationProperty, String associationValue) {
        boolean duplicate = storage.findByAssociation(associationProperty, associationValue).stream()
                .anyMatch(instance -> instance.sagaType().equals(saga.typeName())
                        && !isTerminal(instance.status()));
        if (duplicate) {
            throw new DuplicateSagaInstanceException(saga.type(), associationProperty, associationValue);
        }
    }

    /**
     * Replaces the working copy with {@code status}, {@code steps} and {@code ending}, keeping its pending retry
     * while the new status is {@code CREATED} or {@code RUNNING} and its compensation trigger; returns the instant.
     */
    private Instant evolve(SagaStatus status, Map<String, StepRecord> steps, Ending ending) {
        Instant now = sources.now();
        replace(status, steps, ending, workingCopy.compensationTrigger(), now);
        return now;
    }

    private void replace(SagaStatus status, Map<String, StepRecord> steps, Ending ending,
            CompensationTrigger trigger, Instant now) {
        boolean active = status == SagaStatus.CREATED || status == SagaStatus.RUNNING;
        PendingRetry retry = active ? workingCopy.pendingRetry() : null;
        workingCopy = new SagaInstance(workingCopy.sagaId(), workingCopy.sagaType(), Map.of(),
                workingCopy.associations(), status, workingCopy.createdAt(), now, steps, ending.outcomeStep(),
                ending.failure(), retry, trigger);
    }

    private Map<String, StepRecord> notRunSteps() {
        Map<String, StepRecord> steps = new HashMap<>();
        saga.stepNames().forEach(name -> steps.put(name, StepRecord.notRun()));
        return steps;
    }

    private SagaInstance requireActive(String sagaId, String operation) {
        requireNonNull(sagaId, "sagaId");
        requireHeld(sagaId, operation);
        if (isTerminal(workingCopy.status())) {
            throw InvalidSagaTransitionException.terminal(sagaId, operation, workingCopy.status());
        }
        return workingCopy;
    }

    private void requireHeld(String sagaId, String operation) {
        if (workingCopy == null || !workingCopy.sagaId().equals(sagaId)) {
            throw InvalidSagaTransitionException.notFound(sagaId, operation);
        }
    }

    private void requireEmpty() {
        if (workingCopy != null) {
            throw new IllegalStateException("This lifecycle already holds saga instance " + workingCopy.sagaId());
        }
    }

    private EntryBuilder entry(SagaLifecycleEventKind kind, Instant occurredAt) {
        return new EntryBuilder(workingCopy.sagaId(), kind, occurredAt);
    }

    private static <T> T requireNonNull(T argument, String argumentName) {
        if (argument == null) {
            throw new IllegalArgumentException(argumentName + " must not be null");
        }
        return argument;
    }

    /** Assembles one {@link SagaLifecycleEntry}; the record checks which optional part fits its kind. */
    private static final class EntryBuilder {

        private final String sagaId;
        private final SagaLifecycleEventKind kind;
        private final Instant occurredAt;
        private String stepName;
        private List<AggregateReference> touchedAggregates = List.of();
        private String outcomeStep;
        private SagaFailure failure;
        private CompensationTrigger trigger;

        private EntryBuilder(String sagaId, SagaLifecycleEventKind kind, Instant occurredAt) {
            this.sagaId = sagaId;
            this.kind = kind;
            this.occurredAt = occurredAt;
        }

        EntryBuilder step(String name, List<AggregateReference> touched) {
            this.stepName = name;
            this.touchedAggregates = touched;
            return this;
        }

        EntryBuilder failedStep(String name) {
            this.stepName = name;
            return this;
        }

        EntryBuilder outcome(String step) {
            this.outcomeStep = step;
            return this;
        }

        EntryBuilder failure(SagaFailure cause) {
            this.failure = cause;
            return this;
        }

        EntryBuilder trigger(CompensationTrigger compensationTrigger) {
            this.trigger = compensationTrigger;
            return this;
        }

        SagaLifecycleEntry build() {
            return new SagaLifecycleEntry(sagaId, kind, stepName, touchedAggregates, outcomeStep, failure,
                    occurredAt, trigger);
        }
    }
}
