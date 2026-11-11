package io.github.gkosharovdev.sagaforge.runtime.fixtures.failure;

/** Thrown by {@link FaultPlan} at the armed {@link FaultPoint}. */
public final class InjectedFault extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FaultPoint point;

    InjectedFault(FaultPoint point) {
        super("Failure injected at " + point);
        this.point = point;
    }

    /** Where it was thrown. */
    public FaultPoint point() {
        return point;
    }
}
