package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import java.lang.System.Logger;
import java.time.Duration;
import java.util.function.Function;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;

/** A transport over an {@link InMemoryMessageLog} with a test receiver, no engine. */
public final class TransportUnderTest implements AutoCloseable {

    public final InMemoryTransactionBoundary boundary;
    public final InMemoryMessageLog log;
    public final RecordingLogger logger = new RecordingLogger();
    public final RecordingReceiver receiver;
    public final InMemoryTransport transport;

    public TransportUnderTest(TransportSettings settings, Function<Object, HandlingReport> receive) {
        this(new InMemoryTransactionBoundary(), new InMemoryMessageLog(), settings, receive);
    }

    public TransportUnderTest(InMemoryTransactionBoundary boundary, InMemoryMessageLog log, TransportSettings settings,
            Function<Object, HandlingReport> receive) {
        this.boundary = boundary;
        this.log = log;
        this.receiver = new RecordingReceiver(receive);
        this.transport = InMemoryTransport.builder().log(log).transactions(boundary).settings(settings)
                .logger((Logger) logger).build();
    }

    public TransportUnderTest start() {
        transport.start(receiver, null);
        return this;
    }

    public void publish(Object event) {
        transport.dispatcher().dispatch(event);
    }

    @Override
    public void close() {
        transport.stop(Duration.ofSeconds(2));
    }
}
