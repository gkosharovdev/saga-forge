package io.github.gkosharovdev.sagaforge.engine.fixtures.concurrency;

import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;

/** Creates {@link SignalSaga} objects reporting to one {@link StepProbe}. */
public final class SignalSagaObjects implements SagaObjectProvider {

    private final StepProbe probe;

    public SignalSagaObjects(StepProbe probe) {
        this.probe = probe;
    }

    @Override
    public boolean supports(Class<?> sagaType) {
        return sagaType == SignalSaga.class;
    }

    @Override
    public Object newInstance(Class<?> sagaType) {
        if (!supports(sagaType)) {
            throw new IllegalArgumentException("Not supported: " + sagaType.getName());
        }
        return new SignalSaga(probe);
    }
}
