package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepCompensation;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks that an incoming snapshot is a forward transition from the persisted one ({@link
 * #requireForwardTransition}, contracts/concurrency-control.md). Pure: no database access.
 *
 * <p>Defense in depth against a caller that bypasses the engine: under the engine the instance is
 * already locked by {@code findByIdAndLock} and the check normally passes. Lifecycle history is
 * never derived from snapshots; the engine hands the entries to {@link
 * PostgresStorageProvider#save(SagaInstance, java.util.List)}.
 */
final class ForwardTransition {

  private ForwardTransition() {}

  /**
   * Rejects an {@code incoming} snapshot that is not a forward transition from {@code previous}
   * (contracts/concurrency-control.md "Forward transition"): every step {@code COMPLETED} in {@code
   * previous} must be {@code COMPLETED} in {@code incoming} with an equal {@code touchedAggregates}
   * list, and a terminal ({@code COMPLETED}, {@code FAILED}, {@code COMPENSATED}, {@code
   * COMPENSATION_FAILED}) previous status must be unchanged. Since 006: a completed step keeps its
   * {@code completionSequence}; its compensation only moves {@code null -> NOT_ATTEMPTED |
   * NOTHING_TO_COMPENSATE} and {@code NOT_ATTEMPTED -> COMPENSATED | FAILED}; a recorded
   * compensation trigger never changes; {@code COMPENSATING} only moves to {@code COMPENSATED} or
   * {@code COMPENSATION_FAILED} (specs/006-saga-compensation contracts/storage-delta.md). Since
   * 012: a compensation also moves {@code COMPENSATED -> FAILED}, but only when the same save moves
   * the instance {@code COMPENSATING -> COMPENSATION_FAILED}: a command that compensation sent
   * failed for good ({@code specs/012-outbox-command-failure-feedback} data-model.md). A previously
   * completed step missing from {@code incoming} is a violation; a previously {@code NOT_RUN} step
   * may be missing, and anything else may differ ({@code .specify/memory/memory.md} G-003-15).
   *
   * <p>Not checked, as specified: {@code state}, {@code associations} (G-003-02), {@code sagaType}
   * and {@code createdAt} (G-003-13), the {@code outcome}/{@code failure} of an unchanged terminal
   * status (G-003-19), and non-terminal status order (G-003-20).
   *
   * @param previous the snapshot currently persisted (and locked) for {@code incoming.sagaId()}
   * @param incoming the snapshot being saved
   * @throws ConcurrentSagaUpdateException naming the first violated rule
   */
  static void requireForwardTransition(SagaInstance previous, SagaInstance incoming) {
    String sagaId = incoming.sagaId();
    boolean compensationFailing =
        previous.status() == SagaStatus.COMPENSATING
            && incoming.status() == SagaStatus.COMPENSATION_FAILED;
    for (Map.Entry<String, StepRecord> step : new TreeMap<>(previous.steps()).entrySet()) {
      StepRecord before = step.getValue();
      if (before.status() != StepStatus.COMPLETED) {
        continue;
      }
      StepRecord now = incoming.steps().get(step.getKey());
      if (now == null || now.status() != StepStatus.COMPLETED) {
        throw new ConcurrentSagaUpdateException(
            sagaId,
            "step "
                + step.getKey()
                + " is already COMPLETED but the incoming snapshot has it "
                + (now == null ? "missing" : now.status().name()));
      }
      if (!now.touchedAggregates().equals(before.touchedAggregates())) {
        throw new ConcurrentSagaUpdateException(
            sagaId,
            "completed step "
                + step.getKey()
                + " recorded touchedAggregates "
                + before.touchedAggregates()
                + " but the incoming snapshot has "
                + now.touchedAggregates());
      }
      if (now.attempts() != before.attempts()) {
        throw new ConcurrentSagaUpdateException(
            sagaId,
            "completed step "
                + step.getKey()
                + " recorded attempts "
                + before.attempts()
                + " but the incoming snapshot has "
                + now.attempts());
      }
      if (!now.handledCommands().equals(before.handledCommands())) {
        throw new ConcurrentSagaUpdateException(
            sagaId,
            "completed step "
                + step.getKey()
                + " recorded handledCommands "
                + before.handledCommands()
                + " but the incoming snapshot has "
                + now.handledCommands());
      }
      if (now.completionSequence() != before.completionSequence()) {
        throw new ConcurrentSagaUpdateException(
            sagaId,
            "completed step "
                + step.getKey()
                + " recorded completionSequence "
                + before.completionSequence()
                + " but the incoming snapshot has "
                + now.completionSequence());
      }
      if (!compensationMovesForward(
          before.compensation(), now.compensation(), compensationFailing)) {
        throw new ConcurrentSagaUpdateException(
            sagaId,
            "completed step "
                + step.getKey()
                + " recorded compensation "
                + describe(before.compensation())
                + " but the incoming snapshot has "
                + describe(now.compensation()));
      }
    }
    if (previous.compensationTrigger() != null
        && !previous.compensationTrigger().equals(incoming.compensationTrigger())) {
      throw new ConcurrentSagaUpdateException(
          sagaId,
          "compensation trigger "
              + previous.compensationTrigger()
              + " is recorded but the incoming snapshot has "
              + incoming.compensationTrigger());
    }
    SagaStatus previousStatus = previous.status();
    boolean terminal =
        previousStatus == SagaStatus.COMPLETED
            || previousStatus == SagaStatus.FAILED
            || previousStatus == SagaStatus.COMPENSATED
            || previousStatus == SagaStatus.COMPENSATION_FAILED;
    if (terminal && incoming.status() != previousStatus) {
      throw new ConcurrentSagaUpdateException(
          sagaId,
          "status is terminal "
              + previousStatus
              + " but the incoming snapshot has "
              + incoming.status());
    }
    if (previousStatus == SagaStatus.COMPENSATING
        && !SagaInstance.isCompensation(incoming.status())) {
      throw new ConcurrentSagaUpdateException(
          sagaId, "status is COMPENSATING but the incoming snapshot has " + incoming.status());
    }
  }

  private static boolean compensationMovesForward(
      StepCompensation before, StepCompensation now, boolean compensationFailing) {
    if (before == null) {
      return now == null
          || now.status() == CompensationStatus.NOT_ATTEMPTED
          || now.status() == CompensationStatus.NOTHING_TO_COMPENSATE;
    }
    if (before.status() == CompensationStatus.NOT_ATTEMPTED) {
      return now != null
          && (now.status() == CompensationStatus.NOT_ATTEMPTED
              || now.status() == CompensationStatus.COMPENSATED
              || now.status() == CompensationStatus.FAILED);
    }
    if (compensationFailing
        && before.status() == CompensationStatus.COMPENSATED
        && now != null
        && now.status() == CompensationStatus.FAILED) {
      return true;
    }
    return before.equals(now);
  }

  private static String describe(StepCompensation compensation) {
    return compensation == null ? "none" : compensation.status().name();
  }
}
