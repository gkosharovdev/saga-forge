package io.github.gkosharovdev.sagaforge.core.deadline;

import java.time.Instant;

/**
 * A deadline a saga instance started and that has neither expired nor been cancelled ({@code
 * specs/017-saga-deadlines} FR-014). Persisted with the instance, keyed by the deadline's name.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a component is {@code
 * null} or blank.
 *
 * @param deadlineId this start of the deadline; the id of its stored message
 * @param dueAt when it comes due
 * @param startedByStep the step whose unit of work started it
 * @param startedAt when that unit of work started it
 */
public record OpenDeadline(
    String deadlineId, Instant dueAt, String startedByStep, Instant startedAt) {

  /** Validates every component. */
  public OpenDeadline {
    Checks.requireText(deadlineId, "deadlineId");
    Checks.requireNonNull(dueAt, "dueAt");
    Checks.requireText(startedByStep, "startedByStep");
    Checks.requireNonNull(startedAt, "startedAt");
  }
}
