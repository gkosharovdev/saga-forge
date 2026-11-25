package io.github.gkosharovdev.sagaforge.core.command;

/**
 * The handle of a command stored by {@link CommandGateway#schedule(Object, java.time.Instant)}, used to
 * {@linkplain CommandGateway#cancel(ScheduledCommandId) cancel} it ({@code specs/017-saga-deadlines} FR-007). Its value
 * is the id of the stored message.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code value} is {@code null} or blank.</p>
 *
 * @param value the stored message's id
 */
public record ScheduledCommandId(String value) {

    /** Validates {@code value}. */
    public ScheduledCommandId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
