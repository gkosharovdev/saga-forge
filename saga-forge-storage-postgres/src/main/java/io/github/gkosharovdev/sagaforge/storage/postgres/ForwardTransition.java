package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.util.Map;
import java.util.TreeMap;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;

/**
 * Checks that an incoming snapshot is a forward transition from the persisted one
 * ({@link #requireForwardTransition}, contracts/concurrency-control.md). Pure: no database access.
 *
 * <p>Defense in depth against a caller that bypasses the engine: under the engine the instance is already locked
 * by {@code findByIdAndLock} and the check normally passes. Lifecycle history is never derived from snapshots; the
 * engine hands the entries to {@link PostgresStorageProvider#save(SagaInstance, java.util.List)}.</p>
 */
final class ForwardTransition {

    private ForwardTransition() {
    }

    /**
     * Rejects an {@code incoming} snapshot that is not a forward transition from {@code previous}
     * (contracts/concurrency-control.md "Forward transition"): every step {@code COMPLETED} in {@code previous}
     * must be {@code COMPLETED} in {@code incoming} with an equal {@code touchedAggregates} list, and a terminal
     * ({@code COMPLETED}/{@code FAILED}) previous status must be unchanged. A previously completed step missing
     * from {@code incoming} is a violation; a previously {@code NOT_RUN} step may be missing, and anything else
     * may differ ({@code .specify/memory/memory.md} G-003-15).
     *
     * <p>Not checked, as specified: {@code state}, {@code associations} (G-003-02), {@code sagaType} and
     * {@code createdAt} (G-003-13), the {@code outcome}/{@code failure} of an unchanged terminal status
     * (G-003-19), and non-terminal status order (G-003-20).</p>
     *
     * @param previous the snapshot currently persisted (and locked) for {@code incoming.sagaId()}
     * @param incoming the snapshot being saved
     * @throws ConcurrentSagaUpdateException naming the first violated rule
     */
    static void requireForwardTransition(SagaInstance previous, SagaInstance incoming) {
        String sagaId = incoming.sagaId();
        for (Map.Entry<String, StepRecord> step : new TreeMap<>(previous.steps()).entrySet()) {
            StepRecord before = step.getValue();
            if (before.status() != StepStatus.COMPLETED) {
                continue;
            }
            StepRecord now = incoming.steps().get(step.getKey());
            if (now == null || now.status() != StepStatus.COMPLETED) {
                throw new ConcurrentSagaUpdateException(sagaId, "step " + step.getKey()
                        + " is already COMPLETED but the incoming snapshot has it "
                        + (now == null ? "missing" : now.status().name()));
            }
            if (!now.touchedAggregates().equals(before.touchedAggregates())) {
                throw new ConcurrentSagaUpdateException(sagaId, "completed step " + step.getKey()
                        + " recorded touchedAggregates " + before.touchedAggregates()
                        + " but the incoming snapshot has " + now.touchedAggregates());
            }
        }
        SagaStatus previousStatus = previous.status();
        boolean terminal = previousStatus == SagaStatus.COMPLETED || previousStatus == SagaStatus.FAILED;
        if (terminal && incoming.status() != previousStatus) {
            throw new ConcurrentSagaUpdateException(sagaId, "status is terminal " + previousStatus
                    + " but the incoming snapshot has " + incoming.status());
        }
    }
}
