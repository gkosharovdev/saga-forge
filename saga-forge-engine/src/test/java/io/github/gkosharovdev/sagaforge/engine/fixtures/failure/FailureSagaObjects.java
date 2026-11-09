package io.github.gkosharovdev.sagaforge.engine.fixtures.failure;

import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;

/** Creates {@link FailureSaga} objects with the run's {@link FaultPlan}. */
public final class FailureSagaObjects implements SagaObjectProvider {

    private final FaultPlan plan;

    public FailureSagaObjects(FaultPlan plan) {
        this.plan = plan;
    }

    @Override
    public boolean supports(Class<?> sagaType) {
        return sagaType == FailureSaga.class;
    }

    @Override
    public Object newInstance(Class<?> sagaType) {
        if (!supports(sagaType)) {
            throw new IllegalArgumentException("Not supported: " + sagaType.getName());
        }
        return new FailureSaga(plan);
    }
}
