package io.github.gkosharovdev.sagaforge.core.outcome;

import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import java.time.Instant;
import java.util.Map;

/**
 * A saga instance ended with a failure: a step failed ({@link FailurePhase#STEP}, status {@code
 * FAILED}) or a compensation failed ({@link FailurePhase#COMPENSATION}, status {@code
 * COMPENSATION_FAILED}).
 *
 * <p>It states the fact only. Whether the failure needs a person's attention is the observer's
 * decision: a failed compensation means the runtime has no further way to undo the instance's
 * committed steps, nothing more.
 *
 * <p>Every component is required except {@code compensationTrigger}, which is present if and only
 * if the phase is {@code COMPENSATION}: an {@link IllegalArgumentException} names the component
 * that breaks the rule.
 *
 * @param eventId {@code sagaId + ":FAILED"} or {@code sagaId + ":COMPENSATION_FAILED"}
 * @param sagaType the fully qualified name of the instance's {@code @Saga} class
 * @param sagaId the instance
 * @param associations the instance's associations
 * @param occurredAt when the instance ended
 * @param phase where it failed
 * @param failedStep {@code STEP}: the failed step; {@code COMPENSATION}: the step whose
 *     compensation failed
 * @param classification how the failure was classified
 * @param causeType the fully qualified class name of the failure
 * @param attempts the attempts made, at least 1
 * @param compensationTrigger {@code COMPENSATION}: what started the compensation; {@code STEP}:
 *     {@code null}
 */
public record SagaFailed(
    String eventId,
    String sagaType,
    String sagaId,
    Map<String, String> associations,
    Instant occurredAt,
    FailurePhase phase,
    String failedStep,
    FailureClassification classification,
    String causeType,
    int attempts,
    OutcomeTrigger compensationTrigger)
    implements SagaOutcome {

  /** Validates every component and copies {@code associations}. */
  public SagaFailed {
    Values.requireText(eventId, "eventId");
    Values.requireText(sagaType, "sagaType");
    Values.requireText(sagaId, "sagaId");
    associations = Values.associations(associations);
    Values.requireNonNull(occurredAt, "occurredAt");
    Values.requireNonNull(phase, "phase");
    Values.requireText(failedStep, "failedStep");
    Values.requireNonNull(classification, "classification");
    Values.requireText(causeType, "causeType");
    if (attempts < 1) {
      throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
    }
    if ((phase == FailurePhase.COMPENSATION) != (compensationTrigger != null)) {
      throw new IllegalArgumentException(
          "compensationTrigger must be present if and only if phase is "
              + "COMPENSATION; phase "
              + phase
              + ", compensationTrigger "
              + compensationTrigger);
    }
  }

  /** A step failure of {@code sagaId}, {@code eventId} {@code sagaId:FAILED}. */
  public static SagaFailed inStep(
      String sagaType,
      String sagaId,
      Map<String, String> associations,
      Instant occurredAt,
      String failedStep,
      FailureClassification classification,
      String causeType,
      int attempts) {
    return new SagaFailed(
        Values.eventId(sagaId, "FAILED"),
        sagaType,
        sagaId,
        associations,
        occurredAt,
        FailurePhase.STEP,
        failedStep,
        classification,
        causeType,
        attempts,
        null);
  }

  /**
   * A compensation failure of {@code sagaId}, {@code eventId} {@code sagaId:COMPENSATION_FAILED}.
   */
  public static SagaFailed inCompensation(
      String sagaType,
      String sagaId,
      Map<String, String> associations,
      Instant occurredAt,
      String failedStep,
      FailureClassification classification,
      String causeType,
      int attempts,
      OutcomeTrigger compensationTrigger) {
    return inCompensation(
        sagaType,
        sagaId,
        associations,
        occurredAt,
        failedStep,
        classification,
        causeType,
        attempts,
        compensationTrigger,
        0);
  }

  /**
   * A compensation failure of {@code sagaId} in compensation round {@code compensationRound}:
   * {@code eventId} {@code sagaId:COMPENSATION_FAILED}, with {@code :r<round>} appended from round
   * 1 on.
   */
  public static SagaFailed inCompensation(
      String sagaType,
      String sagaId,
      Map<String, String> associations,
      Instant occurredAt,
      String failedStep,
      FailureClassification classification,
      String causeType,
      int attempts,
      OutcomeTrigger compensationTrigger,
      int compensationRound) {
    return new SagaFailed(
        Values.eventId(sagaId, "COMPENSATION_FAILED", compensationRound),
        sagaType,
        sagaId,
        associations,
        occurredAt,
        FailurePhase.COMPENSATION,
        failedStep,
        classification,
        causeType,
        attempts,
        compensationTrigger);
  }
}
