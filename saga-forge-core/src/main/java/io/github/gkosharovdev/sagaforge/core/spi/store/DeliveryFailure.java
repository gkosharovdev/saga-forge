package io.github.gkosharovdev.sagaforge.core.spi.store;

import java.time.Instant;

/**
 * Why one delivery attempt failed.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code exceptionType} is blank, {@code at} is {@code null} or {@code message}
 *                                  is longer than {@link #MAX_MESSAGE_LENGTH}.</p>
 *
 * @param exceptionType fully qualified class name of the failure; non-blank
 * @param message       the failure's message, at most {@link #MAX_MESSAGE_LENGTH} characters; may be {@code null}
 * @param at            when it failed; non-null
 */
public record DeliveryFailure(String exceptionType, String message, Instant at) {

    /** The longest message kept. */
    public static final int MAX_MESSAGE_LENGTH = 4_000;

    public DeliveryFailure {
        if (exceptionType == null || exceptionType.isBlank()) {
            throw new IllegalArgumentException("exceptionType must not be blank");
        }
        if (at == null) {
            throw new IllegalArgumentException("at must not be null");
        }
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
    }

    /**
     * @param failure the failure; non-null
     * @param at      when it happened; non-null
     * @return the failure's class name and message, the message truncated to {@link #MAX_MESSAGE_LENGTH}
     */
    public static DeliveryFailure of(Throwable failure, Instant at) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        String message = failure.getMessage();
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
        return new DeliveryFailure(failure.getClass().getName(), message, at);
    }
}
