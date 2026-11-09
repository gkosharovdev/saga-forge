package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.List;

/**
 * Thrown by {@link EventReceiver#receive(Object)} after every affected instance was attempted, when for at least one
 * of them no outcome could be recorded: the store was unavailable, the creation write or the {@code FAILED} write
 * failed, or a lock wait was aborted by the store. The transport should redeliver the event.
 *
 * <p>{@link #report()} holds the results that were recorded. The first cause is {@link #getCause()}; the others
 * are {@linkplain #getSuppressed() suppressed}.</p>
 */
public class EventHandlingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HandlingReport report;

    /**
     * Creates the exception.
     *
     * @param message the detail message
     * @param report  the partial report of the results that were recorded; must not be {@code null}
     * @param causes  the failures, at least one; must not be {@code null} or contain {@code null}
     * @throws IllegalArgumentException if {@code report} or {@code causes} is {@code null}, {@code causes} is
     *                                  empty, or it contains {@code null}
     */
    public EventHandlingException(String message, HandlingReport report, List<Throwable> causes) {
        super(message, firstOf(causes));
        this.report = requireReport(report);
        causes.stream().skip(1).forEach(this::addSuppressed);
    }

    /**
     * Returns the results that were recorded before the failures.
     *
     * @return the partial report; never {@code null}
     */
    public HandlingReport report() {
        return report;
    }

    private static Throwable firstOf(List<Throwable> causes) {
        if (causes == null || causes.isEmpty()) {
            throw new IllegalArgumentException("causes must not be null or empty");
        }
        for (Throwable cause : causes) {
            if (cause == null) {
                throw new IllegalArgumentException("causes must not contain null elements");
            }
        }
        return causes.get(0);
    }

    private static HandlingReport requireReport(HandlingReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        return report;
    }
}
