package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.List;

/**
 * The buffered events of one committed unit of work, as given to {@link EventHandoff#handOff(HandedOffEvents)}.
 *
 * @param unitOfWorkId the unit of work's identifier, unique per unit of work, saga-scoped or not; non-null
 * @param sagaType     fully qualified {@code @Saga} class name of the owning instance; non-null if and only if the
 *                     unit of work is saga-scoped
 * @param sagaId       the owning instance; non-null if and only if the unit of work is saga-scoped
 * @param events       the events, in recording order; non-null, non-empty, no {@code null} element; an
 *                     unmodifiable defensive copy
 * @throws IllegalArgumentException if {@code unitOfWorkId}, {@code events} or an element of {@code events} is
 *                                  {@code null}, if {@code events} is empty, or if exactly one of {@code sagaType}
 *                                  and {@code sagaId} is present
 */
public record HandedOffEvents(String unitOfWorkId, String sagaType, String sagaId, List<Object> events) {

    public HandedOffEvents {
        if (unitOfWorkId == null) {
            throw new IllegalArgumentException("unitOfWorkId must not be null");
        }
        if ((sagaType == null) != (sagaId == null)) {
            throw new IllegalArgumentException(
                "sagaType and sagaId must be both present or both absent, but sagaType is " + sagaType
                    + " and sagaId is " + sagaId);
        }
        events = copyEvents(events);
    }

    private static List<Object> copyEvents(List<Object> events) {
        if (events == null) {
            throw new IllegalArgumentException("events must not be null");
        }
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
        for (Object event : events) {
            if (event == null) {
                throw new IllegalArgumentException("events must not contain null elements");
            }
        }
        return List.copyOf(events);
    }
}
