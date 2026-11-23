package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunCompensation.Committed;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunCompensation.Conflicted;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunCompensation.Failed;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunCompensation.Result;
import io.github.gkosharovdev.sagaforge.runtime.internal.RunCompensation.Stopped;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs the remaining compensations of one compensating instance, one unit of work running {@link RunCompensation} each, most
 * recently completed step first, until none is left, one fails, one conflicts, or the instance is found no longer
 * compensating (specs/006-saga-compensation contracts/compensation-run.md "run remaining").
 *
 * <p>The caller holds the instance's in-process lock for the whole run and releases it afterwards; the
 * {@code AFTER_COMMIT} outbox of each committed compensation is added to the caller's list, to run after the
 * release in commit order. A failed compensation is recorded by {@link RecordCompensationFailure} and never retried.</p>
 */
final class CompensationRun {

    /** How the run ended: the outcome to report and the last unit of work that ran, if any. */
    record Summary(HandlingOutcome outcome, String lastUnitOfWorkId) {
    }

    private final Collaborators with;

    CompensationRun(Collaborators with) {
        this.with = with;
    }

    /**
     * @param saga     the saga type of the instance
     * @param sagaId   the compensating instance
     * @param about    the correlation of the handling call
     * @param outboxs where each committed compensation's after-release outbox is added
     * @param ranBefore the unit of work that started compensation in this call, if any; reported when no
     *                  compensation unit of work runs after it
     * @return {@code COMPENSATED}, {@code COMPENSATION_FAILED}, or {@code NOT_HANDLED} if the run lost a conflict
     *         or found the instance moved on by another process
     * @throws RuntimeException if a locked read or the failed-compensation write fails; what committed before stays
     */
    Summary run(SagaModel saga, String sagaId, Correlation about, List<Supplier<List<Throwable>>> outboxs,
            String ranBefore) {
        String last = ranBefore;
        while (true) {
            UnitOfWork unitOfWork = UnitOfWork.forInstance(with, saga, sagaId, about.inUnitOfWork(null));
            Result result = traced(saga, sagaId, unitOfWork);
            switch (result) {
                case Committed committed -> {
                    last = committed.unitOfWorkId();
                    outboxs.add(committed::store);
                    if (committed.instance().status() == SagaStatus.COMPENSATED) {
                        return new Summary(HandlingOutcome.COMPENSATED, last);
                    }
                }
                case Failed failed -> {
                    boolean recorded = RecordCompensationFailure.record(with, saga, failed.correlation(),
                            failed.step(), failed.cause(), outboxs);
                    return new Summary(recorded ? HandlingOutcome.COMPENSATION_FAILED : HandlingOutcome.NOT_HANDLED,
                            unitOfWork.unitOfWorkId());
                }
                case Conflicted conflicted -> {
                    return new Summary(HandlingOutcome.NOT_HANDLED, conflicted.unitOfWorkId());
                }
                case Stopped stopped -> {
                    return new Summary(outcomeOf(stopped.status()), last);
                }
            }
        }
    }

    /** One compensation unit of work inside a {@code saga_forge.compensation} span (010 research §2). */
    private Result traced(SagaModel saga, String sagaId, UnitOfWork unitOfWork) {
        try (SagaInstrumentation.Traced span = with.instrumentation().start(SagaInstrumentation.COMPENSATION)) {
            span.set(SagaInstrumentation.SAGA_TYPE, saga.typeName()).set(SagaInstrumentation.SAGA_ID, sagaId)
                    .set(SagaInstrumentation.UNIT_OF_WORK_ID, unitOfWork.unitOfWorkId());
            Result result;
            try {
                result = RunCompensation.next(with, saga, unitOfWork);
            } catch (RuntimeException | Error failure) {
                span.fail(failure);
                throw failure;
            }
            switch (result) {
                case Committed committed -> {
                    span.set(SagaInstrumentation.STEP_NAME, committed.step())
                            .set(SagaInstrumentation.COMPENSATION_STATUS, committed.instance().steps()
                                    .get(committed.step()).compensation().status().name());
                    if (committed.instance().compensationTrigger() != null) {
                        span.set(SagaInstrumentation.COMPENSATION_TRIGGER,
                                committed.instance().compensationTrigger().kind().name());
                    }
                }
                case Failed failed -> span.set(SagaInstrumentation.STEP_NAME, failed.step())
                        .set(SagaInstrumentation.COMPENSATION_STATUS, "FAILED").fail(failed.cause());
                default -> span.set(SagaInstrumentation.COMPENSATION_STATUS, "NOT_RUN");
            }
            return result;
        }
    }

    private static HandlingOutcome outcomeOf(SagaStatus status) {
        if (status == SagaStatus.COMPENSATED) {
            return HandlingOutcome.COMPENSATED;
        }
        if (status == SagaStatus.COMPENSATION_FAILED) {
            return HandlingOutcome.COMPENSATION_FAILED;
        }
        return HandlingOutcome.NOT_HANDLED;
    }
}
