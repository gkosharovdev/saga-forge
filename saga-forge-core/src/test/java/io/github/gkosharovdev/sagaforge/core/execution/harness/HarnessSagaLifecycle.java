package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.lifecycle.DuplicateSagaInstanceException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.InvalidSagaTransitionException;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaLifecycle;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link SagaLifecycle} that reads and writes every {@link SagaInstance} through an
 * {@link InMemoryStorageProvider}, for the saga test harness.
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <p>Same transition semantics as the feature 001/002 test double {@code InMemorySagaLifecycle}, but it holds
 * no state of its own: because the instances live in the storage provider, the harness's storage snapshot and
 * restore roll lifecycle transitions back together with the rest of a unit of work.</p>
 *
 * <p>Not thread-safe.</p>
 */
public final class HarnessSagaLifecycle implements SagaLifecycle {

    private final InMemoryStorageProvider storage;
    private final Clock clock;
    private final Supplier<String> sagaIds;

    /**
     * @param storage where instances are stored; must not be {@code null}
     * @param clock   timestamps for {@code createdAt} / {@code lastModifiedAt}; must not be {@code null}
     * @param sagaIds generates fresh saga ids; must not be {@code null}
     */
    public HarnessSagaLifecycle(InMemoryStorageProvider storage, Clock clock, Supplier<String> sagaIds) {
        requireNonNull(storage, "storage");
        requireNonNull(clock, "clock");
        requireNonNull(sagaIds, "sagaIds");
        this.storage = storage;
        this.clock = clock;
        this.sagaIds = sagaIds;
    }

    /** Lifecycle generating random UUID saga ids. */
    public HarnessSagaLifecycle(InMemoryStorageProvider storage, Clock clock) {
        this(storage, clock, () -> UUID.randomUUID().toString());
    }

    @Override
    public SagaInstance start(Class<?> sagaType, String associationProperty, String associationValue) {
        requireNonNull(sagaType, "sagaType");
        requireNonNull(associationProperty, "associationProperty");
        requireNonNull(associationValue, "associationValue");
        String typeName = sagaType.getName();
        boolean duplicate = storage.findByAssociation(associationProperty, associationValue).stream()
                .anyMatch(instance -> instance.sagaType().equals(typeName) && !isTerminal(instance.status()));
        if (duplicate) {
            throw new DuplicateSagaInstanceException(sagaType, associationProperty, associationValue);
        }
        Instant now = clock.instant();
        SagaInstance created = new SagaInstance(
                sagaIds.get(),
                typeName,
                Map.of(),
                Map.of(associationProperty, associationValue),
                SagaStatus.CREATED,
                now,
                now,
                notRunSteps(sagaType),
                null,
                null);
        storage.save(created);
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
                    "Unknown step '" + stepName + "' for saga instance '" + sagaId + "' of type "
                            + current.sagaType());
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
        SagaInstance current = storage.findById(sagaId)
                .orElseThrow(() -> InvalidSagaTransitionException.notFound(sagaId, operation));
        if (isTerminal(current.status())) {
            throw InvalidSagaTransitionException.terminal(sagaId, operation, current.status());
        }
        return current;
    }

    private SagaInstance store(
            SagaInstance current, SagaStatus status, Map<String, StepRecord> steps, String outcome,
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
        storage.save(updated);
        return updated;
    }

    private static Map<String, StepRecord> notRunSteps(Class<?> sagaType) {
        Map<String, StepRecord> steps = new HashMap<>();
        for (Method method : HarnessReflection.methods(sagaType)) {
            if (method.isAnnotationPresent(SagaStep.class)) {
                steps.put(method.getName(), StepRecord.notRun());
            }
        }
        return steps;
    }

    static boolean isTerminal(SagaStatus status) {
        return status == SagaStatus.COMPLETED || status == SagaStatus.FAILED;
    }

    private static void requireNonNull(Object argument, String argumentName) {
        if (argument == null) {
            throw new IllegalArgumentException(argumentName + " must not be null");
        }
    }
}
