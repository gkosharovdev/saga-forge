package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;

/** Wraps a {@link MapHandlerInstanceProvider}; fails if {@link FaultPoint#HANDLER_INSTANCE_PROVIDER} is armed. */
public final class FailingHandlerInstances implements HandlerInstanceProvider {

    private final FaultPlan plan;
    private final MapHandlerInstanceProvider delegate;

    public FailingHandlerInstances(FaultPlan plan, MapHandlerInstanceProvider delegate) {
        this.plan = plan;
        this.delegate = delegate;
    }

    @Override
    public Object instanceOf(Class<?> handlerType) {
        plan.throwIfArmed(FaultPoint.HANDLER_INSTANCE_PROVIDER);
        return delegate.instanceOf(handlerType);
    }
}
