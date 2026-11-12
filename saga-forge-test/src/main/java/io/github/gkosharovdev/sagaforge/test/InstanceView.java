package io.github.gkosharovdev.sagaforge.test;

import java.util.Map;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;

/**
 * A read-only snapshot of one saga instance as stored, with its {@code SagaContext} values
 * ({@code specs/009-saga-test-fixture} data-model.md).
 *
 * @param stored  the stored instance
 * @param context its {@code SagaContext} values; empty if it has none
 */
public record InstanceView(SagaInstance stored, Map<String, Object> context) {

    public InstanceView {
        if (stored == null) {
            throw new IllegalArgumentException("stored must not be null");
        }
        context = Map.copyOf(context);
    }

    /** Fully qualified class name of the saga. */
    public String sagaType() {
        return stored.sagaType();
    }

    public String sagaId() {
        return stored.sagaId();
    }

    /** Association property → value, as the instance was correlated. */
    public Map<String, String> associations() {
        return stored.associations();
    }

    public SagaStatus status() {
        return stored.status();
    }

    /** The {@code @EndSaga} step that completed the instance, if it completed. */
    public Optional<String> outcomeStep() {
        return Optional.ofNullable(stored.outcome());
    }

    /**
     * The recorded failure: the instance's own ({@code FAILED}), else a failed compensation's
     * ({@code REQUIRES_INTERVENTION}), else the failure that triggered compensation ({@code FINAL}/{@code EXHAUSTED}).
     */
    public Optional<SagaFailure> failure() {
        if (stored.failure() != null) {
            return Optional.of(stored.failure());
        }
        Optional<SagaFailure> compensation = stored.steps().values().stream()
                .map(StepRecord::compensation)
                .filter(step -> step != null && step.failure() != null)
                .map(step -> step.failure())
                .findFirst();
        if (compensation.isPresent()) {
            return compensation;
        }
        return Optional.ofNullable(stored.compensationTrigger()).map(CompensationTrigger::failure);
    }

    /** Why the instance entered compensation, if it did. */
    public Optional<CompensationTrigger> compensationTrigger() {
        return Optional.ofNullable(stored.compensationTrigger());
    }

    /** Step name → record. */
    public Map<String, StepRecord> steps() {
        return stored.steps();
    }

    /** Short form for messages: {@code OrderProcessingSaga[orderId=o-1] (saga-1)}. */
    String describe() {
        StringBuilder text = new StringBuilder(Model.simpleName(sagaType())).append('[');
        String separator = "";
        for (Map.Entry<String, String> association : new java.util.TreeMap<>(associations()).entrySet()) {
            text.append(separator).append(association.getKey()).append('=').append(association.getValue());
            separator = ", ";
        }
        return text.append("] (").append(sagaId()).append(')').toString();
    }
}
