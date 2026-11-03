package io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test probe for {@link ThreeStepSaga}: traces the order in which steps are entered and arms one-shot step
 * failures.
 *
 * <p>Saga instances are created by the harness, so the probe is static and thread-local (the unit of work
 * runs on the test's thread). Call {@link #reset()} before each test.</p>
 */
public final class ThreeStepSagaProbe {

    private static final ThreadLocal<List<String>> ENTERED = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Map<String, RuntimeException>> FAULTS = ThreadLocal.withInitial(HashMap::new);

    private ThreeStepSagaProbe() {
    }

    /** Arms a one-shot failure with a default {@link IllegalStateException} for {@code stepName}. */
    public static void failStep(String stepName) {
        failStep(stepName, new IllegalStateException("Step " + stepName + " failure injected"));
    }

    /**
     * Arms a one-shot failure: the next run of {@code stepName} throws {@code failure} after doing its work.
     *
     * @throws IllegalArgumentException if {@code stepName} is not a step of {@link ThreeStepSaga} or
     *                                  {@code failure} is {@code null}
     */
    public static void failStep(String stepName, RuntimeException failure) {
        if (!ThreeStepSaga.STEPS_IN_EVENT_ORDER.contains(stepName)) {
            throw new IllegalArgumentException("Not a ThreeStepSaga step: " + stepName);
        }
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        FAULTS.get().put(stepName, failure);
    }

    /** Step names in the order the steps were entered on this thread since the last {@link #reset()}. */
    public static List<String> enteredSteps() {
        return List.copyOf(ENTERED.get());
    }

    /** Clears the trace and every armed failure on this thread. */
    public static void reset() {
        ENTERED.get().clear();
        FAULTS.get().clear();
    }

    static void entered(String stepName) {
        ENTERED.get().add(stepName);
    }

    static void throwIfArmed(String stepName) {
        RuntimeException failure = FAULTS.get().remove(stepName);
        if (failure != null) {
            throw failure;
        }
    }
}
