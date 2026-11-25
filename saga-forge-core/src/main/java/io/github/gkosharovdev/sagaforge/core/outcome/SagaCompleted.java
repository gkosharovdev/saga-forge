package io.github.gkosharovdev.sagaforge.core.outcome;

import java.time.Instant;
import java.util.Map;

/**
 * A saga instance ended {@code COMPLETED} at the {@code @EndSaga} step {@code outcomeStep}.
 *
 * <p>Every component is required: an {@link IllegalArgumentException} names the one that is missing
 * or blank.
 *
 * @param eventId {@code sagaId + ":COMPLETED"}
 * @param sagaType the fully qualified name of the instance's {@code @Saga} class
 * @param sagaId the instance
 * @param associations the instance's associations
 * @param occurredAt when the instance ended
 * @param outcomeStep the {@code @EndSaga} step that ended it
 */
public record SagaCompleted(
    String eventId,
    String sagaType,
    String sagaId,
    Map<String, String> associations,
    Instant occurredAt,
    String outcomeStep)
    implements SagaOutcome {

  /** Validates every component and copies {@code associations}. */
  public SagaCompleted {
    Values.requireText(eventId, "eventId");
    Values.requireText(sagaType, "sagaType");
    Values.requireText(sagaId, "sagaId");
    associations = Values.associations(associations);
    Values.requireNonNull(occurredAt, "occurredAt");
    Values.requireText(outcomeStep, "outcomeStep");
  }

  /** The outcome of {@code sagaId} with its {@link #eventId()} derived from the instance. */
  public static SagaCompleted of(
      String sagaType,
      String sagaId,
      Map<String, String> associations,
      Instant occurredAt,
      String outcomeStep) {
    return new SagaCompleted(
        Values.eventId(sagaId, "COMPLETED"),
        sagaType,
        sagaId,
        associations,
        occurredAt,
        outcomeStep);
  }
}
