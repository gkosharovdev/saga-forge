package io.github.gkosharovdev.sagaforge.engine.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Where the engine takes timestamps, saga ids and unit-of-work ids from; replaceable for deterministic tests.
 *
 * <p><strong>Not API.</strong> Public only so that {@code SagaEngine} can hand it to the runtime.</p>
 *
 * <p>Immutable; thread-safe if the clock and suppliers are.</p>
 *
 * @param clock         timestamps of instances, lifecycle entries, context records and failures
 * @param sagaIds       a fresh id per new saga instance
 * @param unitOfWorkIds a fresh id per unit of work, saga-scoped or not
 */
public record TimeAndIdentity(Clock clock, Supplier<String> sagaIds, Supplier<String> unitOfWorkIds) {

    /**
     * @throws IllegalArgumentException if a component is {@code null}
     */
    public TimeAndIdentity {
        if (clock == null || sagaIds == null || unitOfWorkIds == null) {
            throw new IllegalArgumentException("clock, sagaIds and unitOfWorkIds must not be null");
        }
    }

    Instant now() {
        return clock.instant();
    }

    /**
     * @throws IllegalStateException if the supplier returns a {@code null} or blank id
     */
    String newSagaId() {
        return requireId(sagaIds.get(), "saga id");
    }

    /**
     * @throws IllegalStateException if the supplier returns a {@code null} or blank id
     */
    String newUnitOfWorkId() {
        return requireId(unitOfWorkIds.get(), "unit-of-work id");
    }

    private static String requireId(String id, String kind) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("The " + kind + " supplier returned a null or blank id");
        }
        return id;
    }
}
