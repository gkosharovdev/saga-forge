package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;

/**
 * Derives {@link SagaLifecycleEntry}s by diffing the previously persisted snapshot against the incoming one
 * (data-model.md "Derivation algorithm", steps 2, 4 and 5), and checks that the incoming snapshot is a forward
 * transition from the persisted one ({@link #requireForwardTransition}, contracts/concurrency-control.md). Pure:
 * no database access.
 *
 * <p>Known limitations, implemented as specified: a step already {@code COMPLETED} in the first snapshot gets no
 * entry and {@code CREATED} is recorded whatever the first status is ({@code .specify/memory/memory.md}
 * G-003-01); steps completed in one diff are ordered by step name, not true execution order (research.md §5,
 * G-003-04).</p>
 */
final class LifecycleHistoryDerivation {

    private LifecycleHistoryDerivation() {
    }

    /**
     * @param previousOrNull     the snapshot currently persisted for {@code incoming.sagaId()}, or {@code null} on
     *                           the first save
     * @param incoming           the snapshot being saved
     * @param nextSequenceNumber the sequence number of the first derived entry; the rest follow consecutively
     * @return the entries in sequence order: {@code CREATED} first (no previous snapshot only), then one
     *         {@code STEP_COMPLETED} per step that went {@code NOT_RUN} to {@code COMPLETED} (by step name), then a
     *         {@code COMPLETED} or {@code FAILED} entry when the status became terminal; empty when nothing
     *         changed in those dimensions
     */
    static List<SagaLifecycleEntry> derive(SagaInstance previousOrNull, SagaInstance incoming,
                                           long nextSequenceNumber) {
        String sagaId = incoming.sagaId();
        List<SagaLifecycleEntry> entries = new ArrayList<>();
        long sequence = nextSequenceNumber;

        if (previousOrNull == null) {
            entries.add(new SagaLifecycleEntry(sagaId, sequence++, SagaLifecycleEventKind.CREATED, null, List.of(),
                    null, null, incoming.createdAt()));
        } else {
            for (Map.Entry<String, StepRecord> step : newlyCompletedSteps(previousOrNull, incoming).entrySet()) {
                entries.add(new SagaLifecycleEntry(sagaId, sequence++, SagaLifecycleEventKind.STEP_COMPLETED,
                        step.getKey(), step.getValue().touchedAggregates(), null, null, incoming.lastModifiedAt()));
            }
        }

        SagaStatus previousStatus = previousOrNull == null ? null : previousOrNull.status();
        if (incoming.status() != previousStatus) {
            if (incoming.status() == SagaStatus.COMPLETED) {
                entries.add(new SagaLifecycleEntry(sagaId, sequence, SagaLifecycleEventKind.COMPLETED, null,
                        List.of(), incoming.outcome(), null, incoming.lastModifiedAt()));
            } else if (incoming.status() == SagaStatus.FAILED) {
                entries.add(new SagaLifecycleEntry(sagaId, sequence, SagaLifecycleEventKind.FAILED, null,
                        List.of(), null, incoming.failure(), incoming.lastModifiedAt()));
            }
        }
        return List.copyOf(entries);
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

    /**
     * @return the steps {@code COMPLETED} in {@code incoming} but not in {@code previous} (a step absent from
     *         {@code previous} counts as {@code NOT_RUN}), sorted by step name
     */
    private static Map<String, StepRecord> newlyCompletedSteps(SagaInstance previous, SagaInstance incoming) {
        Map<String, StepRecord> completed = new TreeMap<>();
        for (Map.Entry<String, StepRecord> step : incoming.steps().entrySet()) {
            StepRecord before = previous.steps().get(step.getKey());
            boolean wasCompleted = before != null && before.status() == StepStatus.COMPLETED;
            if (step.getValue().status() == StepStatus.COMPLETED && !wasCompleted) {
                completed.put(step.getKey(), step.getValue());
            }
        }
        return completed;
    }
}
