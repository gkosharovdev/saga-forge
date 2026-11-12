package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.List;

/**
 * What {@link EventReceiver#receive(Object)} did with one event: one {@link SagaHandlingResult} per affected saga
 * type and instance.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code event} or {@code results} is {@code null}, or {@code results}
 *                                  contains {@code null}.</p>
 *
 * @param event   the received domain event; non-null
 * @param results one result per affected saga type and instance, in handling order; non-null, no {@code null}
 *                element; an unmodifiable defensive copy
 */
public record HandlingReport(Object event, List<SagaHandlingResult> results) {

    public HandlingReport {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (results == null) {
            throw new IllegalArgumentException("results must not be null");
        }
        for (SagaHandlingResult result : results) {
            if (result == null) {
                throw new IllegalArgumentException("results must not contain null elements");
            }
        }
        results = List.copyOf(results);
    }

    /**
     * Reports whether no registered saga type reacts to the event, in which case nothing happened.
     *
     * @return {@code true} if and only if {@link #results()} is empty
     */
    public boolean ignored() {
        return results.isEmpty();
    }
}
