package io.github.gkosharovdev.sagaforge.transport.inmemory;

import java.lang.System.Logger;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import io.github.gkosharovdev.sagaforge.core.spi.CommandReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.log.LoggedMessage;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageLog;
import io.github.gkosharovdev.sagaforge.transport.inmemory.internal.Relay;

/**
 * An in-process transport whose messages survive a restart: a transactional outbox over a durable
 * {@link MessageLog}, and a relay that delivers committed messages in the same process
 * ({@code specs/007-in-memory-transport}, contracts/in-memory-transport.md).
 *
 * <pre>{@code
 * InMemoryTransport transport = InMemoryTransport.builder()
 *         .log(messageLog)
 *         .transactions(transactionBoundary)
 *         .build();
 * SagaEngine engine = SagaEngine.builder()
 *         .dispatcher(transport.dispatcher())
 *         .outbox(transport.outbox())
 *         // storage, contextStore, transactions, register(...)
 *         .build();
 * engine.start();
 * transport.start(engine, engine.commandReceiver());
 * // ...
 * transport.stop(Duration.ofSeconds(30));
 * }</pre>
 *
 * <p>Delivery is at least once: a message is marked consumed only after its consumer returned. A redelivered event
 * finds its step already completed (the engine reports it as a duplicate); a logged command is marked consumed in the
 * handler's own transaction. For one running instance per message log (FR-021).</p>
 *
 * <p>Thread-safe.</p>
 */
public final class InMemoryTransport {

    private final Relay relay;
    private final EventDispatcher dispatcher;
    private final Outbox outbox;
    private final MessageLogQueries queries;

    private InMemoryTransport(Builder builder) {
        this.relay = new Relay(builder.log, builder.transactions, builder.settings, builder.clock, builder.logger,
                builder.executor);
        this.dispatcher = new TransportDispatcher(relay);
        this.outbox = new TransportOutbox(relay);
        this.queries = new MessageLogQueries(builder.log, relay, builder.clock);
    }

    /** @return a builder; {@code log} and {@code transactions} are required */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The engine's {@code EventDispatcher}: an event published outside any unit of work is appended to the log (in
     * the host's active transaction, or in one of its own) and delivered after commit.
     *
     * @return the dispatcher to pass to {@code SagaEngine.Builder.dispatcher}
     */
    public EventDispatcher dispatcher() {
        return dispatcher;
    }

    /**
     * The engine's {@code Outbox}: {@code IN_TRANSACTION}, supports logged commands, wakes the relay after commit.
     *
     * @return the outbox to pass to {@code SagaEngine.Builder.outbox}
     */
    public Outbox outbox() {
        return outbox;
    }

    /**
     * Starts delivering: unconsumed messages first (in per-key order), then new ones.
     *
     * @param events   where events go, normally the started {@code SagaEngine}; non-null
     * @param commands where logged commands go, normally {@code engine.commandReceiver()}; may be {@code null} if the
     *                 model sends no logged command
     * @throws IllegalStateException if called twice
     */
    public void start(EventReceiver events, CommandReceiver commands) {
        relay.start(events, commands);
    }

    /**
     * Stops leasing, waits up to {@code timeout} for deliveries in progress, and releases the rest so the next start
     * delivers them (FR-020). Idempotent.
     */
    public void stop(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be null or negative");
        }
        relay.stop(timeout);
    }

    /** @return whether the transport is delivering */
    public boolean isRunning() {
        return relay.isRunning();
    }

    /** @return the read and redelivery operations for operators (FR-022, FR-023) */
    public MessageLogQueries queries() {
        return queries;
    }

    /**
     * Registers a listener called after each event message was delivered and marked consumed, for example to
     * re-publish it on the host's own event mechanism (FR-027). A throwing listener is logged and ignored.
     */
    public void onDelivered(Consumer<LoggedMessage> listener) {
        relay.onDelivered(listener);
    }

    /** Removes consumed messages older than the retention now; returns how many. Runs on its own schedule too. */
    int sweepRetention() {
        return relay.sweepRetention();
    }

    /** @return the identity this transport leases under */
    String consumerId() {
        return relay.consumerId();
    }

    private record TransportDispatcher(Relay relay) implements EventDispatcher {

        @Override
        public void dispatch(Object domainEvent) {
            relay.publish(domainEvent);
        }

        @Override
        public void registerSagaType(Class<?> sagaClass) {
            if (sagaClass == null) {
                throw new IllegalArgumentException("sagaClass must not be null");
            }
        }
    }

    private record TransportOutbox(Relay relay) implements Outbox {

        @Override
        public Timing timing() {
            return Timing.IN_TRANSACTION;
        }

        @Override
        public boolean supportsCommands() {
            return true;
        }

        @Override
        public void store(OutgoingMessages batch) {
            relay.store(batch);
        }

        @Override
        public void committed(OutgoingMessages batch) {
            relay.signal();
        }
    }

    /** Assembles an {@link InMemoryTransport}. */
    public static final class Builder {

        private MessageLog log;
        private TransactionBoundary transactions;
        private Executor executor;
        private TransportSettings settings = TransportSettings.defaults();
        private Clock clock = Clock.systemUTC();
        private Logger logger = System.getLogger("io.github.gkosharovdev.sagaforge.transport");

        private Builder() {
        }

        /** Required: the durable log, normally in the same datastore as saga state (FR-005). */
        public Builder log(MessageLog value) {
            this.log = requireNonNull(value, "log");
            return this;
        }

        /** Required: the boundary the engine and the log share. */
        public Builder transactions(TransactionBoundary value) {
            this.transactions = requireNonNull(value, "transactions");
            return this;
        }

        /** Optional: a host-managed executor for deliveries; default an internal pool of {@code workers} threads. */
        public Builder executor(Executor value) {
            this.executor = requireNonNull(value, "executor");
            return this;
        }

        /** Optional: tuning; default {@link TransportSettings#defaults()}. */
        public Builder settings(TransportSettings value) {
            this.settings = requireNonNull(value, "settings");
            return this;
        }

        /** Optional: the clock for recording, leases, backoff and retention; default UTC system clock. */
        public Builder clock(Clock value) {
            this.clock = requireNonNull(value, "clock");
            return this;
        }

        /** Optional: where the transport logs; default {@code io.github.gkosharovdev.sagaforge.transport}. */
        public Builder logger(Logger value) {
            this.logger = requireNonNull(value, "logger");
            return this;
        }

        /**
         * @return the transport, not yet started
         * @throws IllegalArgumentException if {@code log} or {@code transactions} is missing
         */
        public InMemoryTransport build() {
            if (log == null) {
                throw new IllegalArgumentException("log is required");
            }
            if (transactions == null) {
                throw new IllegalArgumentException("transactions is required");
            }
            return new InMemoryTransport(this);
        }

        private static <T> T requireNonNull(T value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " must not be null");
            }
            return value;
        }
    }
}
