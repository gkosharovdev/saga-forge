package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Starts compensation after a {@code FINAL} or {@code EXHAUSTED} step failure, in a unit of work of its own, where 005
 * wrote {@code FAILED} (specs/006-saga-compensation research §7, FR-003, FR-006, FR-006a; contracts/compensation-run.md).
 *
 * <p>A missing or terminal instance records nothing, like {@link RecordFailure}; an instance with no {@code COMPLETED}
 * step (the start step's unit of work rolled back) is written {@code FAILED} by {@link RecordFailure}, as before 006;
 * otherwise the instance enters {@code COMPENSATING} with a {@code FINAL_FAILURE} or {@code RETRIES_EXHAUSTED} trigger
 * carrying the failure.</p>
 */
final class StartCompensation implements UnitOfWork.Work<CompensationTrigger, StartCompensation.NotStarted> {

    /** Why the work did not run. */
    enum NotStarted { MISSING_OR_TERMINAL, NOTHING_COMMITTED }

    private final SagaFailure failure;

    private StartCompensation(SagaFailure failure) {
        this.failure = failure;
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
            switch (UnitOfWork.forInstance(with, saga, about.sagaId(), about).run(new StartCompensation(failure))) {
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
        if (!InstanceChecks.anyStepCompleted(locked.get())) {
            return Admission.skip(NotStarted.NOTHING_COMMITTED);
        }
        return Admission.proceed();
    }

    @Override
    public CompensationTrigger perform(WorkContext context) {
        CompensationTrigger trigger = CompensationTrigger.failed(failure, context.settings().sources().now());
        context.lifecycle().startCompensation(context.sagaId(), trigger);
        return trigger;
    }
}
