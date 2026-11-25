package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Moves an instance into {@code COMPENSATING} with the trigger it was built with, in a unit of work of its own: after a
 * {@code FINAL} or {@code EXHAUSTED} step failure, where 005 wrote {@code FAILED} (specs/006-saga-compensation
 * research §7, FR-003, FR-006, FR-006a; contracts/compensation-run.md), or after a transport reported a failed outbox
 * command (specs/012-outbox-command-failure-feedback contracts/runtime-reaction.md §A).
 *
 * <p>A missing or terminal instance, or one already compensating, records nothing. An instance with no
 * {@code COMPLETED} step (the start step's unit of work rolled back) records nothing either; after a step failure the
 * caller then writes {@code FAILED} with {@link RecordFailure}, as before 006.</p>
 */
final class StartCompensation implements UnitOfWork.Work<CompensationTrigger, StartCompensation.NotStarted> {

    /** Why the work did not run. */
    enum NotStarted { MISSING_OR_TERMINAL, ALREADY_COMPENSATING, NOTHING_COMMITTED }

    private final CompensationTrigger trigger;
    private final Consumer<LocalTransaction> inTransaction;

    private StartCompensation(CompensationTrigger trigger, Consumer<LocalTransaction> inTransaction) {
        this.trigger = trigger;
        this.inTransaction = inTransaction;
    }

    /** Starts compensation with {@code trigger}, a {@code FINAL_FAILURE} or {@code RETRIES_EXHAUSTED} trigger. */
    static StartCompensation afterStepFailure(CompensationTrigger trigger) {
        return new StartCompensation(trigger, transaction -> { });
    }

    /**
     * Starts compensation with {@code trigger}, a {@code COMMAND_FAILED} trigger, and calls {@code inTransaction}
     * with the unit of work's transaction, so the transport's set-aside commits or rolls back with the start.
     */
    static StartCompensation afterCommandFailure(CompensationTrigger trigger,
            Consumer<LocalTransaction> inTransaction) {
        return new StartCompensation(trigger, inTransaction);
    }

    /**
     * @param about   the correlation of the rolled-back unit of work (saga type, instance and unit of work set)
     * @param failure the step failure, classified {@code FINAL} or {@code EXHAUSTED}
     * @param cause   the exception that rolled the unit of work back; {@code null} when a redelivery finds the
     *                retries exhausted without running
     * @param started whether this event's creation write created the instance
     * @param outboxs where a committed start adds its outbox step, which the caller runs after releasing the
     *                instance lock (011: the start write carries {@code SagaCompensated} when nothing is left to
     *                compensate)
     * @return empty if compensation started (the caller runs it); otherwise the result to report: {@code FAILED},
     *         written or not recorded
     * @throws RuntimeException the failure of this write, after rolling back; nothing was recorded
     */
    static Optional<RecordFailure.Written> record(Collaborators with, SagaModel saga, Correlation about,
            SagaFailure failure, Throwable cause, boolean started, List<Supplier<List<Throwable>>> outboxs) {
        Diagnostics diagnostics = with.diagnostics();
        try {
            switch (UnitOfWork.forInstance(with, saga, about.sagaId(), about).run(afterStepFailure(CompensationTrigger.failed(failure, with.settings().sources().now())))) {
                case UnitOfWork.Skipped<CompensationTrigger, NotStarted> skipped -> {
                    if (skipped.reason() == NotStarted.NOTHING_COMMITTED) {
                        return Optional.of(RecordFailure.record(with, saga, about, failure, cause, started));
                    }
                    diagnostics.instanceFailedNotRecorded(about, cause != null ? cause
                            : new IllegalStateException(failure.causeType() + ": " + failure.message()));
                    return Optional.of(new RecordFailure.Written(new SagaHandlingResult(saga.typeName(),
                            about.sagaId(), HandlingOutcome.FAILED, started, null, failure, about.unitOfWorkId(),
                            List.of()), List::of));
                }
                case UnitOfWork.Committed<CompensationTrigger, NotStarted> committed -> {
                    diagnostics.compensationStarted(about, committed.value());
                    outboxs.add(committed::release);
                    return Optional.empty();
                }
                case UnitOfWork.Failed<CompensationTrigger, NotStarted> failed -> throw UnitOfWork.propagate(failed.cause());
                case UnitOfWork.Conflicted<CompensationTrigger, NotStarted> conflicted -> throw conflicted.conflict();
            }
        } catch (RuntimeException | Error writeFailure) {
            if (cause != null && writeFailure != cause) {
                writeFailure.addSuppressed(cause);
            }
            diagnostics.compensationNotRecorded(about, writeFailure);
            throw writeFailure;
        }
    }

    @Override
    public Admission<NotStarted> admit(Optional<SagaInstance> locked) {
        if (InstanceChecks.missingOrTerminal(locked)) {
            return Admission.skip(NotStarted.MISSING_OR_TERMINAL);
        }
        if (locked.get().status() == SagaStatus.COMPENSATING) {
            return Admission.skip(NotStarted.ALREADY_COMPENSATING);
        }
        if (!InstanceChecks.anyStepCompleted(locked.get())) {
            return Admission.skip(NotStarted.NOTHING_COMMITTED);
        }
        return Admission.proceed();
    }

    @Override
    public CompensationTrigger perform(WorkContext context) {
        context.lifecycle().startCompensation(context.sagaId(), trigger);
        inTransaction.accept(context.transaction());
        return trigger;
    }
}
