package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import java.util.List;
import java.util.Optional;

/**
 * The {@code FAILED} write after a rolled-back unit of work (data-model.md row 9, research.md §7, FR-011, FR-024).
 *
 * <p>Runs in a transaction of its own: {@code begin()}, {@code findByIdAndLock(sagaId)}, then
 * {@code fail(sagaId, failure)} applied to the instance <em>as now stored</em>, {@code save(failed, [FAILED])},
 * {@code commit()}, and logs {@code saga.instance.failed}. It always updates the committed creation record and never
 * creates one. If the stored instance is missing or already terminal, nothing is written: the transaction rolls back,
 * {@code saga.instance.failedNotRecorded} is logged, and the result is still {@code FAILED} with the failure, the
 * instance unchanged.</p>
 *
 * <p>A failure of this write itself (store unavailable, lock wait aborted, write rejected) rolls back, is logged as
 * {@code saga.instance.failedNotRecorded} and propagates: no outcome was recorded for the instance, and the event
 * router reports it through {@code EventHandlingException} (FR-005).</p>
 *
 * <p><strong>G-ENG-10.</strong> Between the unit of work's rollback and this write's lock another process may run a
 * unit of work on the same instance. This write then marks an instance that has moved on, or finds it terminal and
 * records nothing. The in-process instance lock (US4) closes the window within one engine only.</p>
 *
 * <p>Immutable; thread-safe if the host plug-ins are. Each call runs on the calling thread.</p>
 */
final class FailedWrite {

    private final SagaModel saga;
    private final EngineSettings settings;
    private final Diagnostics diagnostics;

    /**
     * @param saga        the saga type of the instance to mark
     * @param settings    storage, transactions and clock
     * @param diagnostics where the outcome is logged
     */
    FailedWrite(SagaModel saga, EngineSettings settings, Diagnostics diagnostics) {
        this.saga = saga;
        this.settings = settings;
        this.diagnostics = diagnostics;
    }

    /**
     * Marks the instance {@code FAILED} with {@code failure}.
     *
     * @param about   the correlation of the rolled-back unit of work (saga type and instance set)
     * @param failure what failed, built by the unit of work
     * @param cause   the exception that rolled the unit of work back; added as suppressed to a failure of this write;
     *                {@code null} when a redelivery finds the retries exhausted without running (005)
     * @param started whether this event's creation write created the instance
     * @return a {@code FAILED} result carrying {@code failure} and the rolled-back unit of work's id
     * @throws RuntimeException the failure of this write, after rolling back; nothing was recorded
     */
    SagaHandlingResult record(Correlation about, SagaFailure failure, Throwable cause, boolean started) {
        String sagaId = about.sagaId();
        LocalTransaction transaction = settings.transactions().begin();
        try {
            Optional<SagaInstance> stored = settings.storage().findByIdAndLock(sagaId);
            if (stored.isEmpty() || EngineSagaLifecycle.isTerminal(stored.get().status())) {
                transaction.rollback();
                diagnostics.instanceFailedNotRecorded(about, cause != null ? cause
                        : new IllegalStateException(failure.causeType() + ": " + failure.message()));
            } else {
                markFailed(stored.get(), failure);
                transaction.commit();
                diagnostics.instanceFailed(about, failure);
            }
        } catch (RuntimeException | Error writeFailure) {
            LocalTransactions.rollBackAfter(settings.transactions(), transaction, writeFailure);
            if (cause != null && writeFailure != cause) {
                writeFailure.addSuppressed(cause);
            }
            diagnostics.instanceFailedNotRecorded(about, writeFailure);
            throw writeFailure;
        }
        return new SagaHandlingResult(saga.typeName(), sagaId, HandlingOutcome.FAILED, started, null, failure,
                about.unitOfWorkId(), List.of());
    }

    private void markFailed(SagaInstance stored, SagaFailure failure) {
        EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(saga, settings.storage(), settings.sources());
        lifecycle.load(stored);
        lifecycle.fail(stored.sagaId(), failure);
        PendingWrite write = lifecycle.drain(stored.sagaId());
        settings.storage().save(write.instance(), write.entries());
    }
}
