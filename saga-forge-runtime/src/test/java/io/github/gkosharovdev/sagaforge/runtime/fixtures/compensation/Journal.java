package io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What steps and compensations ran, in order, with the trigger each compensation received; shared by every saga
 * object. {@link #failOn} makes a named entry throw after it was journaled; {@link #swallowFailures} tells a
 * compensation to catch the failure of a command it sends.
 */
public final class Journal {

    private final List<String> compensations = new ArrayList<>();
    private final List<CompensationTrigger> triggers = new ArrayList<>();
    private final Map<String, RuntimeException> failures = new HashMap<>();
    private boolean swallowFailures;

    public void ran(String compensation, CompensationTrigger trigger) {
        compensations.add(compensation);
        if (trigger != null) {
            triggers.add(trigger);
        }
        RuntimeException failure = failures.get(compensation);
        if (failure != null) {
            throw failure;
        }
    }

    /** Every later {@link #ran} of {@code name} throws {@code failure} after journaling it. */
    public void failOn(String name, RuntimeException failure) {
        failures.put(name, failure);
    }

    public void clearFailures() {
        failures.clear();
    }

    public void swallowFailures(boolean swallow) {
        this.swallowFailures = swallow;
    }

    public boolean swallowsFailures() {
        return swallowFailures;
    }

    public List<String> compensations() {
        return List.copyOf(compensations);
    }

    public List<CompensationTrigger> triggers() {
        return List.copyOf(triggers);
    }
}
