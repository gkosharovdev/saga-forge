package io.github.gkosharovdev.sagaforge.spring.transport;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/**
 * Starts the transport with the application, after the {@link SagaRuntime} started, and stops it first on shutdown
 * ({@code specs/007-in-memory-transport} FR-020, {@code specs/008-spring-boot-starter} FR-005). Without a started
 * {@code SagaRuntime} it does nothing.
 */
public final class TransportLifecycle implements SmartLifecycle {

    /** Starts late and stops early relative to the default phase, inside the web server's graceful shutdown. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

    private final InMemoryTransport transport;
    private final ObjectProvider<SagaRuntime> runtimes;
    private final ObjectProvider<EventReceiverCustomizer> customizers;
    private final Duration shutdownTimeout;
    private volatile boolean running;

    public TransportLifecycle(InMemoryTransport transport, ObjectProvider<SagaRuntime> runtimes,
            ObjectProvider<EventReceiverCustomizer> customizers, Duration shutdownTimeout) {
        this.transport = transport;
        this.runtimes = runtimes;
        this.customizers = customizers;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public void start() {
        SagaRuntime runtime = runtimes.getIfAvailable();
        if (runtime == null) {
            return;
        }
        if (!runtime.isStarted()) {
            // SagaRuntimeLifecycle starts the runtime one phase earlier (008); without it there is nothing to deliver to.
            return;
        }
        EventReceiver receiver = runtime;
        for (EventReceiverCustomizer customizer : customizers.orderedStream().toList()) {
            receiver = customizer.customize(receiver);
        }
        transport.start(receiver, runtime.commandReceiver());
        running = true;
    }

    @Override
    public void stop() {
        if (running) {
            transport.stop(shutdownTimeout);
            running = false;
        }
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
