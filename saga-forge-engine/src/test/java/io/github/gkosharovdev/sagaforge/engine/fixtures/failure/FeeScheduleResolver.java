package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;

/** Supplies {@link FeeSchedule} parameters; fails if {@link FaultPoint#PARAMETER_RESOLVER} is armed. */
public final class FeeScheduleResolver implements HandlerParameterResolver {

    private final FaultPlan plan;
    private final FeeSchedule schedule;

    public FeeScheduleResolver(FaultPlan plan, FeeSchedule schedule) {
        this.plan = plan;
        this.schedule = schedule;
    }

    @Override
    public boolean supports(Executable handler, Parameter parameter) {
        return parameter.getType() == FeeSchedule.class;
    }

    @Override
    public Object resolve(Executable handler, Parameter parameter) {
        plan.throwIfArmed(FaultPoint.PARAMETER_RESOLVER);
        return schedule;
    }
}
