package io.github.gkosharovdev.sagaforge.spring.runtime;

import org.springframework.context.SmartLifecycle;

import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.transport.TransportLifecycle;

/**
 * Starts the {@link SagaRuntime} after the context refreshed, one phase before the transport starts delivering
 * ({@code specs/008-spring-boot-starter} FR-001, FR-005). The runtime has no stop of its own: stopping the transport,
 * which happens first, stops all delivery into it.
 */
public final class SagaRuntimeLifecycle implements SmartLifecycle {

    /** One phase before {@link TransportLifecycle#PHASE}: starts first, stops last. */
    public static final int PHASE = TransportLifecycle.PHASE - 1;

    private final SagaRuntime runtime;
    private volatile boolean running;

    public SagaRuntimeLifecycle(SagaRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void start() {
        if (!runtime.isStarted()) {
            runtime.start();
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
