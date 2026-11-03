package io.github.gkosharovdev.sagaforge.core.command;

import java.util.Objects;

/**
 * Thrown when the target aggregate of an instance-method {@code @CommandHandler} cannot be found.
 *
 * <p>Raised by {@link CommandGateway#send(Object)} when the client's {@code @AggregateLoader} method, or
 * the host's {@code AggregateResolver}, finds no aggregate for the command's {@code @AggregateIdentifier}
 * value. The exception carries the aggregate type and id so callers and logs can pinpoint the missing
 * aggregate.
 */
public class AggregateNotFoundException extends RuntimeException {

    private final Class<?> aggregateType;
    private final Object aggregateId;

    /**
     * Creates a new aggregate-not-found exception.
     *
     * @param aggregateType type of the aggregate that was looked up
     * @param aggregateId   identifier value that was looked up
     * @throws NullPointerException if any argument is {@code null}
     */
    public AggregateNotFoundException(Class<?> aggregateType, Object aggregateId) {
        super(buildMessage(
            Objects.requireNonNull(aggregateType, "aggregateType"),
            Objects.requireNonNull(aggregateId, "aggregateId")));
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
    }

    /**
     * @return type of the aggregate that was looked up
     */
    public Class<?> getAggregateType() {
        return aggregateType;
    }

    /**
     * @return identifier value that was looked up
     */
    public Object getAggregateId() {
        return aggregateId;
    }

    private static String buildMessage(Class<?> aggregateType, Object aggregateId) {
        return "No aggregate " + aggregateType.getName() + " found with id '" + aggregateId + "'";
    }
}
