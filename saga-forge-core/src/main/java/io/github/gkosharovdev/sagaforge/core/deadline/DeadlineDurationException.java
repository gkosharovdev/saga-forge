package io.github.gkosharovdev.sagaforge.core.deadline;

import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;

/**
 * A deadline's duration could not be read from the saga's context: the attribute named by
 * {@code @StartDeadline(durationFrom = ...)} is missing or does not hold an ISO-8601 duration
 * ({@code specs/017-saga-deadlines} FR-009). Thrown by the runtime when the starting step
 * completes. It is a {@link FinalFailure}: a retry would read the same value, so the step's unit of
 * work fails without retry.
 */
public class DeadlineDurationException extends IllegalStateException implements FinalFailure {

  private static final long serialVersionUID = 1L;

  /**
   * @param message names the saga, the deadline and the attribute
   */
  public DeadlineDurationException(String message) {
    super(message);
  }

  /**
   * @param message names the saga, the deadline and the attribute
   * @param cause the parse failure
   */
  public DeadlineDurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
