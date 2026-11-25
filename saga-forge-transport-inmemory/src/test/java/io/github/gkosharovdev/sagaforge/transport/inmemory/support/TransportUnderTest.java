package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import java.lang.System.Logger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.spi.CommandReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;

/** A transport over an {@link InMemoryMessageStore} with a test receiver, no engine. */
public final class TransportUnderTest implements AutoCloseable {

    public final InMemoryTransactionBoundary boundary;
    public final InMemoryMessageStore log;
    public final RecordingLogger logger = new RecordingLogger();
    public final RecordingReceiver receiver;
    public final InMemoryTransport transport;

    public TransportUnderTest(TransportSettings settings, Function<Object, HandlingReport> receive) {
        this(new InMemoryTransactionBoundary(), new InMemoryMessageStore(), settings, receive);
    }

    public TransportUnderTest(InMemoryTransactionBoundary boundary, InMemoryMessageStore log, TransportSettings settings,
            Function<Object, HandlingReport> receive) {
        this.boundary = boundary;
        this.log = log;
        this.receiver = new RecordingReceiver(receive);
        this.transport = InMemoryTransport.builder().messageStore(log).transactions(boundary).settings(settings)
                .logger((Logger) logger).build();
    }

    public TransportUnderTest start() {
        transport.start(receiver, null);
        return this;
    }

    /** Starts delivering, outbox commands to {@code commands}. */
    public TransportUnderTest start(CommandReceiver commands) {
        transport.start(receiver, commands);
        return this;
    }

    /**
     * Appends an outbox command to the log, as a committed unit of work would.
     *
     * @return the message id
     */
    public String sendCommand(Object command, CommandId commandId) {
        String messageId = UUID.randomUUID().toString();
        String type = command.getClass().getName();
        String[] source = commandId.sagaScoped() ? new String[] {"test.Saga", commandId.sagaId()} : new String[2];
        LocalTransaction tx = boundary.begin();
        log.append(tx, List.of(new NewMessage(messageId, MessageKind.COMMAND, type, command, commandId,
                type + "#" + messageId, source[0], source[1], Instant.now())));
        tx.commit();
        return messageId;
    }

    public void publish(Object event) {
        transport.dispatcher().dispatch(event);
    }

    @Override
    public void close() {
        transport.stop(Duration.ofSeconds(2));
    }
}
