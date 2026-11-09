package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import java.util.List;
import java.util.Optional;

/**
 * Starts compensation after a {@code FINAL} or {@code EXHAUSTED} step failure, in its own transaction, where 005
 * wrote {@code FAILED} (specs/006-saga-compensation research §7, FR-003, FR-006, FR-006a; contracts/compensation-run.md).
 *
 * <p>Under {@code findByIdAndLock}: a missing or terminal instance records nothing, like {@link FailedWrite}; an
 * instance with no {@code COMPLETED} step (the start step's unit of work rolled back) is written {@code FAILED} by
 * {@link FailedWrite}, as before 006; otherwise the instance enters {@code COMPENSATING} with a
 * {@code FINAL_FAILURE} or {@code RETRIES_EXHAUSTED} trigger carrying {@code failure}.</p>
 */
final class CompensationStartWrite {

    private final SagaModel saga;
    private final EngineSettings settings;
    private final Diagnostics diagnostics;

    CompensationStartWrite(SagaModel saga, EngineSettings settings, Diagnostics diagnostics) {
        this.saga = saga;
        this.settings = settings;
        this.diagnostics = diagnostics;
    }

    /**
     * @param about   the correlation of the rolled-back unit of work (saga type and instance set)
     * @param failure the step failure, classified {@code FINAL} or {@code EXHAUSTED}
     * @param cause   the exception that rolled the unit of work back; {@code null} when a redelivery finds the
     *                retries exhausted without running
     * @param started whether this event's creation write created the instance
     * @return empty if compensation started (the caller runs it); otherwise the result to report: {@code FAILED},
     *         written or not recorded
     * @throws RuntimeException the failure of this write, after rolling back; nothing was recorded
     */
    Optional<SagaHandlingResult> record(Correlation about, SagaFailure failure, Throwable cause, boolean started) {
        String sagaId = about.sagaId();
        LocalTransaction transaction = settings.transactions().begin();
        try {
            Optional<SagaInstance> stored = settings.storage().findByIdAndLock(sagaId);
            if (stored.isEmpty() || EngineSagaLifecycle.isTerminal(stored.get().status())) {
                transaction.rollback();
                diagnostics.instanceFailedNotRecorded(about, cause != null ? cause
                        : new IllegalStateException(failure.causeType() + ": " + failure.message()));
                return Optional.of(failedResult(sagaId, failure, about, started));
            }
            if (stored.get().steps().values().stream().noneMatch(step -> step.status() == StepStatus.COMPLETED)) {
                transaction.rollback();
                return Optional.of(new FailedWrite(saga, settings, diagnostics).record(about, failure, cause, started));
            }
            CompensationTrigger trigger = CompensationTrigger.failed(failure, settings.sources().now());
            EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(saga, settings.storage(), settings.sources());
            lifecycle.load(stored.get());
            lifecycle.startCompensation(sagaId, trigger);
            PendingWrite write = lifecycle.drain(sagaId);
            settings.storage().save(write.instance(), write.entries());
            transaction.commit();
            diagnostics.compensationStarted(about, trigger);
            return Optional.empty();
        } catch (RuntimeException | Error writeFailure) {
            LocalTransactions.rollBackAfter(settings.transactions(), transaction, writeFailure);
            if (cause != null && writeFailure != cause) {
                writeFailure.addSuppressed(cause);
            }
            diagnostics.compensationNotRecorded(about, writeFailure);
            throw writeFailure;
        }
    }

    private SagaHandlingResult failedResult(String sagaId, SagaFailure failure, Correlation about, boolean started) {
        return new SagaHandlingResult(saga.typeName(), sagaId, HandlingOutcome.FAILED, started, null, failure,
                about.unitOfWorkId(), List.of());
    }
}
