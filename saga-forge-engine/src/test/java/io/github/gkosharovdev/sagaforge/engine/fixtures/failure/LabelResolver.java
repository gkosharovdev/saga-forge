package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Host {@link AggregateResolver} for {@link Label}; load and save fail if {@link FaultPoint#RESOLVER_LOAD} or
 * {@code RESOLVER_SAVE} is armed.
 */
public final class LabelResolver implements AggregateResolver, Snapshottable {

    private final FaultPlan plan;
    private final Map<String, Label> labels = new LinkedHashMap<>();

    public LabelResolver(FaultPlan plan) {
        this.plan = plan;
    }

    /** Seeds a blank label. */
    public void add(String labelId) {
        labels.put(labelId, new Label(labelId, plan));
    }

    @Override
    public boolean supports(Class<?> aggregateType) {
        return aggregateType == Label.class;
    }

    @Override
    public Optional<Object> load(Class<?> aggregateType, Object aggregateId) {
        plan.throwIfArmed(FaultPoint.RESOLVER_LOAD);
        return Optional.ofNullable(labels.get(String.valueOf(aggregateId)));
    }

    @Override
    public void save(Object aggregate) {
        plan.throwIfArmed(FaultPoint.RESOLVER_SAVE);
        Label label = (Label) aggregate;
        labels.put(label.labelId(), label);
    }

    /** Label id to the parcel it was printed for, for every printed label. */
    public Map<String, String> printed() {
        Map<String, String> printed = new LinkedHashMap<>();
        labels.values().forEach(label -> label.printedFor().ifPresent(parcel -> printed.put(label.labelId(), parcel)));
        return Map.copyOf(printed);
    }

    @Override
    public Object snapshot() {
        return labels.values().stream().map(Label::copy).toList();
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof List<?> state)) {
            throw new IllegalArgumentException("Not a LabelResolver snapshot: " + snapshot);
        }
        labels.clear();
        state.forEach(label -> labels.put(((Label) label).labelId(), ((Label) label).copy()));
    }
}
