package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import java.util.Optional;

/**
 * Records a failed compensation in its own transaction, after the compensation's unit of work rolled back
 * (specs/006-saga-compensation research §9, FR-016, SC-007): the step's compensation becomes {@code FAILED} and the
 * instance {@code REQUIRES_INTERVENTION}; steps not reached stay {@code NOT_ATTEMPTED}. Nothing is retried.
 *
 * <p>The failure is classified with 005's {@link FailureClassifier} and the default one-attempt policy:
 * {@code FINAL}, {@code UNCLASSIFIED}, or {@code EXHAUSTED} for a transient cause after its single attempt. If the
 * locked instance is no longer compensating, or the step no longer waits for its compensation (another process moved
 * on), nothing is written and {@link #record} returns {@code false}.</p>
 */
final class CompensationFailedWrite {

    private final SagaModel saga;
    private final EngineSettings settings;
    private final Diagnostics diagnostics;

    CompensationFailedWrite(SagaModel saga, EngineSettings settings, Diagnostics diagnostics) {
        this.saga = saga;
        this.settings = settings;
        this.diagnostics = diagnostics;
    }

    /**
     * @param about the correlation of the rolled-back compensation unit of work
     * @param step  the step whose compensation failed
     * @param cause the failure that rolled it back; added as suppressed to a failure of this write
     * @return {@code true} if the instance now requires intervention
     * @throws RuntimeException the failure of this write, after rolling back; nothing was recorded
     */
    boolean record(Correlation about, String step, Throwable cause) {
        String sagaId = about.sagaId();
        LocalTransaction transaction = settings.transactions().begin();
        try {
            Optional<SagaInstance> stored = settings.storage().findByIdAndLock(sagaId);
            if (stored.isEmpty() || !waitsForCompensation(stored.get(), step)) {
                transaction.rollback();
                diagnostics.compensationNotRecorded(about, cause);
                return false;
            }
            SagaFailure failure = classified(step, cause);
            EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(saga, settings.storage(), settings.sources());
            lifecycle.load(stored.get());
            lifecycle.recordCompensationFailed(sagaId, step, failure);
            PendingWrite write = lifecycle.drain(sagaId);
            settings.storage().save(write.instance(), write.entries());
            transaction.commit();
            diagnostics.compensationFailed(about, failure);
            return true;
        } catch (RuntimeException | Error writeFailure) {
            LocalTransactions.rollBackAfter(settings.transactions(), transaction, writeFailure);
            if (writeFailure != cause) {
                writeFailure.addSuppressed(cause);
            }
            diagnostics.compensationNotRecorded(about, writeFailure);
            throw writeFailure;
        }
    }

    private SagaFailure classified(String step, Throwable cause) {
        FailureClassification classification = FailureClassifier.classify(cause, SagaModel.defaultRetryPolicy());
        FailureClassification recorded = classification == FailureClassification.TRANSIENT
                ? FailureClassification.EXHAUSTED
                : classification;
        return SagaFailure.of(step, cause, settings.sources().now(), recorded, 1);
    }

    private static boolean waitsForCompensation(SagaInstance instance, String step) {
        StepRecord record = instance.steps().get(step);
        return instance.status() == SagaStatus.COMPENSATING && record != null && record.compensation() != null
                && record.compensation().status() == CompensationStatus.NOT_ATTEMPTED;
    }
}
