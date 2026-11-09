package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import java.util.HashMap;
import java.util.Map;

/**
 * Which {@link FaultPoint} fails next, shared by every object of one {@link FailureSaga} run. One-shot: the armed
 * point throws {@link InjectedFault} once and disarms. A {@linkplain #duringStep step action} runs once, at the end of
 * its step's own work and before the step's own fault point: the way to arm a storage or commit fault after the
 * creation write, from inside the unit of work.
 *
 * <p>Test-only. Confined to the test's thread.</p>
 */
public final class FaultPlan {

    private final Map<String, Runnable> stepActions = new HashMap<>();
    private FaultPoint armed;
    private boolean catchInFeeStep;

    /** Makes {@code point} fail the next time it is reached. */
    public FaultPlan arm(FaultPoint point) {
        if (point == null) {
            throw new IllegalArgumentException("point must not be null");
        }
        this.armed = point;
        return this;
    }

    /** Makes {@code onFeeCharged} catch the {@link InjectedFault} its {@code ReserveSlot} send raises. */
    public FaultPlan catchInFeeStep() {
        this.catchInFeeStep = true;
        return this;
    }

    /** Runs {@code action} once, the next time step {@code stepName} of {@link FailureSaga} has done its work. */
    public FaultPlan duringStep(String stepName, Runnable action) {
        if (!FailureSaga.STEPS_IN_EVENT_ORDER.contains(stepName)) {
            throw new IllegalArgumentException("Not a FailureSaga step: " + stepName);
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        stepActions.put(stepName, action);
        return this;
    }

    /** Disarms every point, drops every step action and clears {@link #catchInFeeStep()}. */
    public void reset() {
        armed = null;
        catchInFeeStep = false;
        stepActions.clear();
    }

    /** Runs and drops the action registered for {@code stepName}, then throws if {@code point} is armed. */
    void endOfStep(String stepName, FaultPoint point) {
        Runnable action = stepActions.remove(stepName);
        if (action != null) {
            action.run();
        }
        throwIfArmed(point);
    }

    boolean catchesInFeeStep() {
        return catchInFeeStep;
    }

    /** Throws {@link InjectedFault} if {@code point} is armed, disarming it. */
    void throwIfArmed(FaultPoint point) {
        if (armed == point) {
            armed = null;
            throw new InjectedFault(point);
        }
    }
}
