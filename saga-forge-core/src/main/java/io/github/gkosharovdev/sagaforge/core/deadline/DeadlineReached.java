package io.github.gkosharovdev.sagaforge.core.deadline;

import java.time.Instant;

/**
 * A saga deadline came due ({@code specs/017-saga-deadlines} FR-010, FR-011). Stored in the message
 * store when the deadline starts and delivered to the runtime once {@code dueAt} has passed,
 * through the transport's ordinary event delivery. It is addressed to one saga instance by {@code
 * sagaType} and {@code sagaId}: it is not a {@code @DomainEvent} and is never routed by
 * association.
 *
 * <p>A {@code @DeadlineExpired} step may declare one parameter of this type to read when the
 * deadline came due and which step started it.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if a component is {@code
 * null} or blank.
 *
 * @param sagaType fully qualified class name of the {@code @Saga}
 * @param sagaId the saga instance
 * @param deadlineName the deadline's name, as declared by {@code @StartDeadline(name = ...)}
 * @param deadlineId this start of the deadline; equals the id of the stored message. A deadline
 *     started again gets a new id, so a delivery of the old one is recognised as replaced
 * @param dueAt when the deadline came due
 * @param startedByStep the step whose unit of work started it
 */
public record DeadlineReached(
    String sagaType,
    String sagaId,
    String deadlineName,
    String deadlineId,
    Instant dueAt,
    String startedByStep) {

  /** Validates every component. */
  public DeadlineReached {
    Checks.requireText(sagaType, "sagaType");
    Checks.requireText(sagaId, "sagaId");
    Checks.requireText(deadlineName, "deadlineName");
    Checks.requireText(deadlineId, "deadlineId");
    Checks.requireNonNull(dueAt, "dueAt");
    Checks.requireText(startedByStep, "startedByStep");
  }

  /**
   * @return {@code deadlineId}, which identifies this delivery
   */
  public String eventId() {
    return deadlineId;
  }
}
