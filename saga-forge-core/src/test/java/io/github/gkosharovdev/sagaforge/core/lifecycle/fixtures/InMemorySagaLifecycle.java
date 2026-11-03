package io.github.gkosharovdev.sagaforge.core.lifecycle.fixtures;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.lifecycle.DuplicateSagaInstanceException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.InvalidSagaTransitionException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaLifecycle;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;

/**
 * Test-only {@link SagaLifecycle} double used to exercise the contract. Not a production
 * implementation; none ships in {@code saga-forge-core}, and this class must never move to
 * {@code src/main}.
 *
 * <p>Backed by its own (non-static) {@code Map<String, SagaInstance>} keyed by {@code sagaId}, so two
 * {@code InMemorySagaLifecycle} objects never share instances. Enforces the contract's pre-conditions
 * and the transition diagram:</p>
 * <pre>
 * start                                   --&gt; CREATED   (every step NOT_RUN)
 * CREATED / RUNNING --advance (wait point)--&gt; RUNNING
 * CREATED / RUNNING --complete(@EndSaga)---&gt; COMPLETED (outcome recorded; terminal)
 * CREATED / RUNNING --fail----------------&gt; FAILED    (failure recorded; terminal)
 * </pre>
 *
 * <p>{@link #start(Class, String, String)} builds {@code steps} from the {@code @SagaStep}-annotated
 * declared methods of the saga type. {@link SagaStatus#COMPLETED} and {@link SagaStatus#FAILED} are
 * terminal. As the interface documents, verifying that {@code outcomeStep} is an {@code @EndSaga} step
 * before {@link #complete(String, String)} is the caller's responsibility; this double does not check
 * it.</p>
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
            now,
            notRunSteps(sagaType),
            null,
            null);
        instances.put(created.sagaId(), created);
        return created;
    }

    @Override
    public SagaInstance recordStepCompleted(
            String sagaId, String stepName, List<AggregateReference> touchedAggregates) {
        requireNonNull(sagaId, "sagaId");
        requireNonNull(stepName, "stepName");
        requireNonNull(touchedAggregates, "touchedAggregates");
        SagaInstance current = requireActive(sagaId, "recordStepCompleted");
        if (!current.steps().containsKey(stepName)) {
            throw new IllegalArgumentException(
                "Unknown step '" + stepName + "' for saga instance '" + sagaId + "' of type " + current.sagaType());
        }
        Map<String, StepRecord> steps = new HashMap<>(current.steps());
        steps.put(stepName, StepRecord.completed(touchedAggregates));
        return store(current, current.status(), steps, null, null);
    }

    @Override
    public SagaInstance advance(String sagaId) {
        requireNonNull(sagaId, "sagaId");
        SagaInstance current = requireActive(sagaId, "advance");
        return store(current, SagaStatus.RUNNING, current.steps(), null, null);
    }

    @Override
    public SagaInstance complete(String sagaId, String outcomeStep) {
        requireNonNull(sagaId, "sagaId");
        requireNonNull(outcomeStep, "outcomeStep");
        SagaInstance current = requireActive(sagaId, "complete");
        return store(current, SagaStatus.COMPLETED, current.steps(), outcomeStep, null);
    }

    @Override
    public SagaInstance fail(String sagaId, SagaFailure failure) {
        requireNonNull(sagaId, "sagaId");
        requireNonNull(failure, "failure");
        SagaInstance current = requireActive(sagaId, "fail");
        return store(current, SagaStatus.FAILED, current.steps(), null, failure);
    }

    private SagaInstance requireActive(String sagaId, String operation) {
        SagaInstance current = instances.get(sagaId);
        if (current == null) {
            throw InvalidSagaTransitionException.notFound(sagaId, operation);
        }
        if (isTerminal(current.status())) {
            throw InvalidSagaTransitionException.terminal(sagaId, operation, current.status());
        }
        return current;
    }

    private SagaInstance store(
            SagaInstance current,
            SagaStatus status,
            Map<String, StepRecord> steps,
            String outcome,
            SagaFailure failure) {
        SagaInstance updated = new SagaInstance(
            current.sagaId(),
            current.sagaType(),
            current.state(),
            current.associations(),
            status,
            current.createdAt(),
            clock.instant(),
            steps,
            outcome,
            failure);
        instances.put(updated.sagaId(), updated);
        return updated;
    }

    private static Map<String, StepRecord> notRunSteps(Class<?> sagaType) {
        Map<String, StepRecord> steps = new HashMap<>();
        for (Method method : sagaType.getDeclaredMethods()) {
            if (method.isAnnotationPresent(SagaStep.class)) {
                steps.put(method.getName(), StepRecord.notRun());
            }
        }
        return steps;
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
