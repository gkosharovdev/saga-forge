package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.internal.CompensationUnitOfWork.Committed;
import io.github.gkosharovdev.sagaforge.engine.internal.CompensationUnitOfWork.Conflicted;
import io.github.gkosharovdev.sagaforge.engine.internal.CompensationUnitOfWork.Failed;
import io.github.gkosharovdev.sagaforge.engine.internal.CompensationUnitOfWork.Result;
import io.github.gkosharovdev.sagaforge.engine.internal.CompensationUnitOfWork.Stopped;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Collaborators;
import java.util.List;
import java.util.function.Supplier;

/**
 * Runs the remaining compensations of one compensating instance, one {@link CompensationUnitOfWork} each, most
 * recently completed step first, until none is left, one fails, one conflicts, or the instance is found no longer
 * compensating (specs/006-saga-compensation contracts/compensation-run.md "run remaining").
 *
 * <p>The caller holds the instance's in-process lock for the whole run and releases it afterwards; the
 * {@code AFTER_COMMIT} outbox of each committed compensation is added to the caller's list, to run after the
 * release in commit order. A failed compensation is recorded by {@link CompensationFailedWrite} and never retried.</p>
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
     * @return {@code COMPENSATED}, {@code REQUIRES_INTERVENTION}, or {@code NOT_HANDLED} if the run lost a conflict
     *         or found the instance moved on by another process
     * @throws RuntimeException if a locked read or the failed-compensation write fails; what committed before stays
     */
    Summary run(SagaModel saga, String sagaId, Correlation about, List<Supplier<List<Throwable>>> outboxs,
            String ranBefore) {
        String last = ranBefore;
        while (true) {
            CompensationUnitOfWork unitOfWork = new CompensationUnitOfWork(saga, sagaId, with, about);
            Result result = unitOfWork.run();
            switch (result) {
                case Committed committed -> {
                    last = committed.unitOfWorkId();
                    outboxs.add(committed::store);
                    if (committed.instance().status() == SagaStatus.COMPENSATED) {
                        return new Summary(HandlingOutcome.COMPENSATED, last);
                    }
                }
                case Failed failed -> {
                    boolean recorded = new CompensationFailedWrite(saga, with.settings(), with.diagnostics())
                            .record(failed.correlation(), failed.step(), failed.cause());
                    return new Summary(recorded ? HandlingOutcome.REQUIRES_INTERVENTION : HandlingOutcome.NOT_HANDLED,
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

    private static HandlingOutcome outcomeOf(SagaStatus status) {
        if (status == SagaStatus.COMPENSATED) {
            return HandlingOutcome.COMPENSATED;
        }
        if (status == SagaStatus.REQUIRES_INTERVENTION) {
            return HandlingOutcome.REQUIRES_INTERVENTION;
        }
        return HandlingOutcome.NOT_HANDLED;
    }
}
