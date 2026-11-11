package io.github.gkosharovdev.sagaforge.runtime.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service-hosted handler of {@link ChargeFee}; its second parameter comes from {@link FeeScheduleResolver}. Records the
 * fee, fails if {@link FaultPoint#FEE_HANDLER} is armed, then records {@link FeeCharged}.
 */
public final class FeeService implements Snapshottable {

    private final FaultPlan plan;
    private final Map<String, Long> fees = new LinkedHashMap<>();

    public FeeService(FaultPlan plan) {
        this.plan = plan;
    }

    /** The fee id this service uses for a parcel. */
    public static String feeIdFor(String parcelId) {
        return "fee-" + parcelId;
    }

    @CommandHandler
    public void handle(ChargeFee command, FeeSchedule schedule) {
        String feeId = feeIdFor(command.parcelId());
        fees.put(feeId, schedule.feeFor(command.weight()));
        plan.throwIfArmed(FaultPoint.FEE_HANDLER);
        DomainEvents.record(new FeeCharged(feeId, command.parcelId()));
    }

    /** Fee amounts by fee id, in charge order; a copy. */
    public Map<String, Long> fees() {
        return Map.copyOf(fees);
    }

    @Override
    public Object snapshot() {
        return Map.copyOf(fees);
    }

    @Override
    public void restore(Object snapshot) {
        if (!(snapshot instanceof Map<?, ?> state)) {
            throw new IllegalArgumentException("Not a FeeService snapshot: " + snapshot);
        }
        fees.clear();
        state.forEach((feeId, amount) -> fees.put((String) feeId, (Long) amount));
    }
}
