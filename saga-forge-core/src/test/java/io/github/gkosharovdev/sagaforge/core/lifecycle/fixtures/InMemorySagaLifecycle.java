package io.github.gkosharovdev.sagaforge.core.lifecycle.fixtures;

import io.github.gkosharovdev.sagaforge.core.lifecycle.DuplicateSagaInstanceException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.InvalidSagaTransitionException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaLifecycle;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test-only {@link SagaLifecycle} double used to exercise the contract. Not a production
 * implementation; none ships in {@code saga-forge-core}, and this class must never move to
 * {@code src/main}.
 *
 * <p>Backed by its own (non-static) {@code Map<String, SagaInstance>} keyed by {@code sagaId}, so two
 * {@code InMemorySagaLifecycle} objects never share instances. Enforces the contract's pre-conditions
 * and the transition diagram:</p>
 * <pre>
 * CREATED --advance--&gt; RUNNING --advance--&gt; RUNNING --complete--&gt; COMPLETED (terminal)
 * CREATED --complete (single-step saga)------------------------&gt; COMPLETED (terminal)
 * </pre>
 *
 * <p>{@link SagaStatus#COMPLETED} and {@link SagaStatus#FAILED} are terminal. As the interface
 * documents, verifying that the current step is {@code @EndSaga} before {@link #complete(String)} is the
 * caller's responsibility; this double does not check it.</p>
 *
 * <p>Timestamps come from an injectable {@link Clock} so tests can be deterministic.</p>
 */
public final class InMemorySagaLifecycle implements SagaLifecycle {

    private final Map<String, SagaInstance> instances = new HashMap<>();
    private final Clock clock;

    /**
     * Creates a double whose timestamps come from {@link Clock#systemUTC()}.
     */
    public InMemorySagaLifecycle() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a double whose timestamps come from {@code clock}.
     *
     * @param clock source of {@code createdAt}/{@code lastModifiedAt}; must not be {@code null}
     */
    public InMemorySagaLifecycle(Clock clock) {
        requireNonNull(clock, "clock");
        this.clock = clock;
    }

    @Override
    public SagaInstance start(Class<?> sagaType, String associationProperty, String associationValue) {
        requireNonNull(sagaType, "sagaType");
        requireNonNull(associationProperty, "associationProperty");
        requireNonNull(associationValue, "associationValue");
        String typeName = sagaType.getName();
        boolean duplicate = instances.values().stream()
            .filter(instance -> !isTerminal(instance.status()))
            .filter(instance -> instance.sagaType().equals(typeName))
            .anyMatch(instance -> associationValue.equals(instance.associations().get(associationProperty)));
        if (duplicate) {
            throw new DuplicateSagaInstanceException(sagaType, associationProperty, associationValue);
        }
        Instant now = clock.instant();
        SagaInstance created = new SagaInstance(
            UUID.randomUUID().toString(),
            typeName,
            Map.of(),
            Map.of(associationProperty, associationValue),
            SagaStatus.CREATED,
            now,
            now);
        instances.put(created.sagaId(), created);
        return created;
    }

    @Override
    public SagaInstance advance(String sagaId) {
        return transition(sagaId, "advance", SagaStatus.RUNNING);
    }

    @Override
    public SagaInstance complete(String sagaId) {
        return transition(sagaId, "complete", SagaStatus.COMPLETED);
    }

    private SagaInstance transition(String sagaId, String operation, SagaStatus target) {
        requireNonNull(sagaId, "sagaId");
        SagaInstance current = instances.get(sagaId);
        if (current == null) {
            throw InvalidSagaTransitionException.notFound(sagaId, operation);
        }
        if (isTerminal(current.status())) {
            throw InvalidSagaTransitionException.terminal(sagaId, operation, current.status());
        }
        SagaInstance updated = new SagaInstance(
            current.sagaId(),
            current.sagaType(),
            current.state(),
            current.associations(),
            target,
            current.createdAt(),
            clock.instant());
        instances.put(sagaId, updated);
        return updated;
    }

    private static boolean isTerminal(SagaStatus status) {
        return status == SagaStatus.COMPLETED || status == SagaStatus.FAILED;
    }

    private static void requireNonNull(Object argument, String argumentName) {
        if (argument == null) {
            throw new IllegalArgumentException(argumentName + " must not be null");
        }
    }
}
