package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

/** A collaborator of {@link FeeService} that is no {@code @Command}: supplied by {@link FeeScheduleResolver}. */
public final class FeeSchedule {

    private final long centsPerKilogram;

    public FeeSchedule(long centsPerKilogram) {
        this.centsPerKilogram = centsPerKilogram;
    }

    long feeFor(long weight) {
        return weight * centsPerKilogram;
    }
}
