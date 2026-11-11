package io.github.gkosharovdev.sagaforge.spring.transport;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;

/**
 * Starts the transport with the application and stops it first on shutdown ({@code specs/007-in-memory-transport}
 * FR-020, FR-029). Starts the host's {@link SagaEngine} bean if it is not started yet, then
 * {@code transport.start(engine, engine.commandReceiver())}. Without a {@code SagaEngine} bean it does nothing.
 */
public final class TransportLifecycle implements SmartLifecycle {

    /** Starts late and stops early relative to the default phase, inside the web server's graceful shutdown. */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;

    private final InMemoryTransport transport;
    private final ObjectProvider<SagaEngine> engines;
    private final ObjectProvider<EventReceiverCustomizer> customizers;
    private final Duration shutdownTimeout;
    private volatile boolean running;

    public TransportLifecycle(InMemoryTransport transport, ObjectProvider<SagaEngine> engines,
            ObjectProvider<EventReceiverCustomizer> customizers, Duration shutdownTimeout) {
        this.transport = transport;
        this.engines = engines;
        this.customizers = customizers;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public void start() {
        SagaEngine engine = engines.getIfAvailable();
        if (engine == null) {
            return;
        }
        if (!engine.isStarted()) {
            engine.start();
        }
        EventReceiver receiver = engine;
        for (EventReceiverCustomizer customizer : customizers.orderedStream().toList()) {
            receiver = customizer.customize(receiver);
        }
        transport.start(receiver, engine.commandReceiver());
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
