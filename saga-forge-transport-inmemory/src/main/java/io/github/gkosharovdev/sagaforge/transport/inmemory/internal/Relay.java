package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import io.github.gkosharovdev.sagaforge.core.spi.CommandReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeasedMessages;
import io.github.gkosharovdev.sagaforge.core.spi.store.ConsumerHeartbeat;
import io.github.gkosharovdev.sagaforge.core.spi.store.DeliveryFailure;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaseLostException;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.UnreadableMessageException;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;

/**
 * The transport's runtime: appends outgoing messages to the {@link MessageStore} and delivers committed ones in
 * process ({@code specs/007-in-memory-transport} research §1, §5, §6, §9, §12).
 *
 * <p><strong>Not API.</strong> Public only because {@code InMemoryTransport} lives in another package.</p>
 *
 * <ul>
 *   <li>A coordinator thread leases due messages when signalled or every poll interval, and hands each to a keyed
 *       worker lane; the store lets only the head of each ordering key be leased, so at most one message per key is
 *       in flight.</li>
 *   <li>An event is delivered to {@link EventReceiver#receive(Object)} and then marked consumed in a transaction of
 *       its own; a command goes to {@link CommandReceiver#receive(OutboxCommand)} and is marked consumed inside the
 *       handler's transaction.</li>
 *   <li>A failure records the attempt and makes the message due again after a growing backoff; at
 *       {@code maxAttempts} it is set aside. An unreadable payload is set aside at once.</li>
 *   <li>The same thread renews the leases of deliveries still running, refreshes the heartbeat and removes consumed
 *       messages older than the retention.</li>
 * </ul>
 */
public final class Relay {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final MessageStore messageStore;
    private final TransactionBoundary transactions;
    private final TransportSettings settings;
    private final Clock clock;
    private final Logger logger;
    private final Executor hostExecutor;
    private final TransportInstrumentation instrumentation;
    private final String consumerId = UUID.randomUUID().toString();
    private final Map<String, StoredMessage> inFlight = new ConcurrentHashMap<>();
    private final List<Consumer<StoredMessage>> deliveredListeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wakeUp = lock.newCondition();

    private boolean signalled;
    private volatile State state = State.CREATED;
    private EventReceiver events;
    private CommandReceiver commands;
    private KeyedWorkers workers;
    private Thread coordinator;
    private Instant startedAt;
    private Instant nextHeartbeat;
    private Instant nextSweep;
    private Instant nextRenewal;

    private enum State { CREATED, RUNNING, STOPPING, STOPPED }

    public Relay(MessageStore messageStore, TransactionBoundary transactions, TransportSettings settings, Clock clock,
            Logger logger, Executor hostExecutor, TransportInstrumentation instrumentation) {
        this.messageStore = messageStore;
        this.transactions = transactions;
        this.settings = settings;
        this.clock = clock;
        this.logger = logger;
        this.hostExecutor = hostExecutor;
        this.instrumentation = instrumentation;
    }

    // ----- appending -----------------------------------------------------------------------------------------

    /** Appends the batch in the active transaction ({@code Outbox.store}, IN_TRANSACTION). */
    public void store(OutgoingMessages batch) {
        LocalTransaction tx = transactions.current().orElseThrow(() -> new IllegalStateException(
                "the in-memory transport's outbox needs the unit of work's transaction to be active"));
        messageStore.append(tx, LogAppender.entries(batch, clock.instant(), instrumentation.currentContext()));
    }

    /**
     * Appends an event published outside any unit of work: in the host's active transaction if there is one,
     * otherwise in a transaction of its own, then signals.
     */
    public void publish(Object event) {
        if (event == null) {
            throw new IllegalArgumentException("domainEvent must not be null");
        }
        NewMessage entry = LogAppender.published(event, clock.instant(), instrumentation.currentContext());
        var active = transactions.current();
        if (active.isPresent()) {
            messageStore.append(active.get(), List.of(entry));
            return;
        }
        LocalTransaction tx = transactions.begin();
        try {
            messageStore.append(tx, List.of(entry));
            tx.commit();
        } catch (RuntimeException | Error failure) {
            rollBackQuietly(tx, failure);
            throw failure;
        }
        signal();
    }

    /** Wakes the coordinator; never blocks for long. */
    public void signal() {
        lock.lock();
        try {
            signalled = true;
            wakeUp.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // ----- lifecycle -----------------------------------------------------------------------------------------

    public synchronized void start(EventReceiver eventReceiver, CommandReceiver commandReceiver) {
        if (state != State.CREATED) {
            throw new IllegalStateException("start() was already called; the transport is " + state);
        }
        if (eventReceiver == null) {
            throw new IllegalArgumentException("eventReceiver must not be null");
        }
        this.events = eventReceiver;
        this.commands = commandReceiver;
        this.workers = new KeyedWorkers(settings.workers(), hostExecutor);
        Instant now = clock.instant();
        this.startedAt = now;
        warnAboutOtherConsumers(now);
        beat(now);
        this.nextHeartbeat = now.plus(settings.heartbeatInterval());
        this.nextSweep = now;
        this.nextRenewal = now.plus(settings.leaseDuration().dividedBy(2));
        state = State.RUNNING;
        coordinator = new Thread(this::coordinate, "saga-forge-transport-relay");
        coordinator.setDaemon(true);
        coordinator.start();
        logger.log(Level.INFO, () -> "saga.transport.started consumer=" + consumerId + " workers="
                + settings.workers());
    }

    /** Stops leasing, waits up to {@code timeout} for running deliveries, then releases the remaining leases. */
    public void stop(Duration timeout) {
        Thread running;
        synchronized (this) {
            if (state != State.RUNNING) {
                state = State.STOPPED;
                return;
            }
            state = State.STOPPING;
            running = coordinator;
        }
        signal();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            running.join(Math.max(1, timeout.toMillis()));
            while (!inFlight.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            workers.shutdown(Math.max(1, (deadline - System.nanoTime()) / NANOS_PER_MILLI));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            messageStore.release(consumerId);
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "saga.transport.releaseFailed consumer=" + consumerId, failure);
        }
        synchronized (this) {
            state = State.STOPPED;
        }
        logger.log(Level.INFO, () -> "saga.transport.stopped consumer=" + consumerId + " unfinished="
                + inFlight.size());
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public String consumerId() {
        return consumerId;
    }

    public void onDelivered(Consumer<StoredMessage> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        deliveredListeners.add(listener);
    }

    /** Removes consumed messages older than the retention now; returns how many (FR-019). */
    public int sweepRetention() {
        Instant cutoff = clock.instant().minus(settings.retention());
        int total = 0;
        int deleted;
        do {
            deleted = messageStore.purgeConsumed(cutoff, 1_000);
            total += deleted;
        } while (deleted == 1_000);
        return total;
    }

    // ----- coordinator ---------------------------------------------------------------------------------------

    private void coordinate() {
        while (state == State.RUNNING) {
            try {
                housekeeping();
                int leased = pollOnce();
                if (leased == 0 || inFlight.size() >= settings.leaseBatchSize()) {
                    awaitSignal();
                }
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "saga.transport.pollFailed consumer=" + consumerId, failure);
                awaitSignal();
            }
        }
    }

    private int pollOnce() {
        int room = settings.leaseBatchSize() - inFlight.size();
        if (room <= 0) {
            return 0;
        }
        LeasedMessages leased = messageStore.lease(consumerId, clock.instant(), settings.leaseDuration(), room);
        for (UnreadableMessageException unreadable : leased.unreadable()) {
            setAside(unreadable.leased(), unreadable, "UNREADABLE: " + unreadable.getMessage());
        }
        for (StoredMessage message : leased.readable()) {
            inFlight.put(message.messageId(), message);
            workers.submit(message.orderingKey(), () -> deliver(message));
        }
        return leased.size();
    }

    private void awaitSignal() {
        lock.lock();
        try {
            if (!signalled && state == State.RUNNING) {
                wakeUp.await(settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
            }
            signalled = false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            state = State.STOPPING;
        } finally {
            lock.unlock();
        }
    }

    private void housekeeping() {
        Instant now = clock.instant();
        if (!now.isBefore(nextRenewal)) {
            renewLeases(now);
            nextRenewal = now.plus(settings.leaseDuration().dividedBy(2));
        }
        if (!now.isBefore(nextHeartbeat)) {
            beat(now);
            nextHeartbeat = now.plus(settings.heartbeatInterval());
        }
        if (!now.isBefore(nextSweep)) {
            try {
                sweepRetention();
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "saga.transport.retentionFailed", failure);
            }
            nextSweep = now.plus(settings.retentionSweepInterval());
        }
    }

    private void renewLeases(Instant now) {
        if (inFlight.isEmpty()) {
            return;
        }
        Set<String> lost = messageStore.renew(consumerId, Set.copyOf(inFlight.keySet()), now.plus(settings.leaseDuration()));
        lost.forEach(id -> logger.log(Level.WARNING, () -> "saga.transport.leaseLost message=" + id));
    }

    private void beat(Instant now) {
        try {
            messageStore.heartbeat(new ConsumerHeartbeat(consumerId, hostName(), startedAt, now));
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "saga.transport.heartbeatFailed consumer=" + consumerId, failure);
        }
    }

    private void warnAboutOtherConsumers(Instant now) {
        try {
            Duration window = settings.heartbeatInterval().multipliedBy(3);
            List<ConsumerHeartbeat> others = messageStore.liveConsumers(now.minus(window)).stream()
                    .filter(beat -> !beat.consumerId().equals(consumerId)).toList();
            if (!others.isEmpty()) {
                logger.log(Level.WARNING, () -> "saga.transport.secondInstance consumer=" + consumerId
                        + " others=" + others.stream().map(b -> b.consumerId() + "@" + b.host()).toList()
                        + " - the in-memory transport supports one running instance per message store");
            }
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "saga.transport.heartbeatFailed consumer=" + consumerId, failure);
        }
    }

    // ----- delivery ------------------------------------------------------------------------------------------

    private void deliver(StoredMessage message) {
        TransportInstrumentation.Delivery span = instrumentation.startDelivery(message);
        try {
            if (message.kind() == MessageKind.EVENT) {
                events.receive(message.payload());
                messageStore.markConsumed(consumerId, message.messageId(), clock.instant());
                notifyDelivered(message);
            } else {
                if (commands == null) {
                    throw new IllegalStateException("no CommandReceiver was given to the transport, but message "
                            + message.messageId() + " is a outbox command");
                }
                commands.receive(new OutboxCommand(message.commandId(), message.payload(),
                        tx -> messageStore.markConsumed(tx, consumerId, message.messageId(), clock.instant())));
            }
            logger.log(Level.DEBUG, () -> "saga.transport.delivered message=" + message.messageId());
        } catch (LeaseLostException lost) {
            logger.log(Level.WARNING, () -> "saga.transport.leaseLost message=" + message.messageId());
        } catch (RuntimeException | Error failure) {
            span.fail(failure);
            failed(message, failure);
        } finally {
            span.close();
            inFlight.remove(message.messageId());
            signal();
        }
    }

    private void failed(StoredMessage message, Throwable failure) {
        Instant now = clock.instant();
        DeliveryFailure recorded = DeliveryFailure.of(failure, now);
        if (message.attempts() >= settings.maxAttempts()) {
            setAside(message, failure, "MAX_ATTEMPTS");
            return;
        }
        Duration wait = Backoff.after(message.attempts(), settings.initialBackoff(), settings.maxBackoff());
        try {
            messageStore.recordFailure(consumerId, message.messageId(), recorded, now.plus(wait));
            logger.log(Level.DEBUG, () -> "saga.transport.deliveryFailed message=" + message.messageId()
                    + " attempts=" + message.attempts() + " retryIn=" + wait);
        } catch (RuntimeException writeFailure) {
            logger.log(Level.WARNING, "saga.transport.failureNotRecorded message=" + message.messageId(),
                    writeFailure);
        }
    }

    private void setAside(StoredMessage message, Throwable failure, String reason) {
        try {
            messageStore.setAside(consumerId, message.messageId(), DeliveryFailure.of(failure, clock.instant()), reason);
            logger.log(Level.WARNING, () -> "saga.transport.setAside message=" + message.messageId() + " type="
                    + message.payloadType() + " attempts=" + message.attempts() + " reason=" + reason, failure);
        } catch (RuntimeException writeFailure) {
            logger.log(Level.WARNING, "saga.transport.setAsideFailed message=" + message.messageId(), writeFailure);
        }
    }

    private void notifyDelivered(StoredMessage message) {
        for (Consumer<StoredMessage> listener : deliveredListeners) {
            try {
                listener.accept(message);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "saga.transport.listenerFailed message=" + message.messageId(), failure);
            }
        }
    }

    private void rollBackQuietly(LocalTransaction tx, Throwable cause) {
        try {
            tx.rollback();
        } catch (RuntimeException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static String hostName() {
        return HostName.VALUE;
    }

    /** Looked up once per JVM: {@code InetAddress.getLocalHost()} can be slow. */
    private static final class HostName {

        static final String VALUE = lookUp();

        private static String lookUp() {
            String fromEnvironment = System.getenv("HOSTNAME");
            if (fromEnvironment != null && !fromEnvironment.isBlank()) {
                return fromEnvironment;
            }
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (Exception unknown) {
                return "unknown";
            }
        }
    }
}
