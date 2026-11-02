package io.github.gkosharovdev.sagaforge.core.lifecycle;

import java.util.Objects;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;

/**
 * Thrown when a lifecycle transition is requested for a saga instance that cannot accept it: either no
 * instance exists for the addressed saga id, or the instance is already in a terminal status.
 *
 * <p>Raised by {@link SagaLifecycle#advance(String)} and {@link SagaLifecycle#complete(String)} so that an invalid
 * transition is reported as a clear, distinguishable error rather than silently succeeding or corrupting
 * the recorded status (FR-006). Instances are created through one of two factories, keeping the two
 * failure modes distinguishable to callers:
 * <ul>
 *   <li>{@link #notFound(String, String)} — no instance exists for the saga id;
 *       {@link #getCurrentStatus()} is empty.</li>
 *   <li>{@link #terminal(String, String, SagaStatus)} — the instance exists but is already terminal;
 *       {@link #getCurrentStatus()} holds its recorded status.</li>
 * </ul>
 */
public class InvalidSagaTransitionException extends RuntimeException {

    private final String sagaId;
    private final String operation;
    private final SagaStatus currentStatus;

    private InvalidSagaTransitionException(String sagaId, String operation, SagaStatus currentStatus) {
        super(buildMessage(sagaId, operation, currentStatus));
        this.sagaId = sagaId;
        this.operation = operation;
        this.currentStatus = currentStatus;
    }

    /**
     * Creates an exception signaling that no saga instance exists for {@code sagaId}.
     *
     * @param sagaId    saga id the transition addressed
     * @param operation lifecycle operation that was attempted (e.g. {@code "advance"}, {@code "complete"})
     * @return a new exception whose {@link #getCurrentStatus()} is empty
     * @throws NullPointerException if any argument is {@code null}
     */
    public static InvalidSagaTransitionException notFound(String sagaId, String operation) {
        return new InvalidSagaTransitionException(
            Objects.requireNonNull(sagaId, "sagaId"),
            Objects.requireNonNull(operation, "operation"),
            null);
    }

    /**
     * Creates an exception signaling that the saga instance for {@code sagaId} is already in a terminal
     * status and cannot accept the attempted transition.
     *
     * @param sagaId        saga id the transition addressed
     * @param operation     lifecycle operation that was attempted (e.g. {@code "advance"}, {@code "complete"})
     * @param currentStatus status the instance is currently recorded in
     * @return a new exception whose {@link #getCurrentStatus()} holds {@code currentStatus}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static InvalidSagaTransitionException terminal(String sagaId, String operation, SagaStatus currentStatus) {
        return new InvalidSagaTransitionException(
            Objects.requireNonNull(sagaId, "sagaId"),
            Objects.requireNonNull(operation, "operation"),
            Objects.requireNonNull(currentStatus, "currentStatus"));
    }

    /**
     * @return saga id the transition addressed
     */
    public String getSagaId() {
        return sagaId;
    }

    /**
     * @return lifecycle operation that was attempted
     */
    public String getOperation() {
        return operation;
    }

    /**
     * @return the instance's current status if it exists and is terminal, or empty if no instance exists
     */
    public Optional<SagaStatus> getCurrentStatus() {
        return Optional.ofNullable(currentStatus);
    }

    /**
     * @return {@code true} if this exception signals a missing instance rather than a terminal one
     */
    public boolean isNotFound() {
        return currentStatus == null;
    }

    private static String buildMessage(String sagaId, String operation, SagaStatus currentStatus) {
        if (currentStatus == null) {
            return "Cannot " + operation + " saga instance '" + sagaId + "': no such instance exists";
        }
        return "Cannot " + operation + " saga instance '" + sagaId + "': instance is already in terminal status "
            + currentStatus;
    }
}
