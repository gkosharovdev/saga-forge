package io.github.gkosharovdev.sagaforge.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;

/**
 * The fixture's transport (research §4): an {@code IN_TRANSACTION} outbox that releases a batch only once its unit of
 * work committed, and a dispatcher; what they hand off waits in one queue that {@link #drain} delivers on the test
 * thread, in order, events to the runtime and outbox commands to its {@code CommandReceiver}, until nothing is left.
 * Delivery is exactly once and synchronous; no lease, redelivery or ordering across aggregates is simulated.
 */
final class LoopBackTransport implements EventDispatcher, Outbox {

    /** Most deliveries one drain makes before it assumes sagas are feeding each other forever. */
    static final int MAX_DELIVERIES = 10_000;

    private final Deque<Object> queue = new ArrayDeque<>();
    private final List<OutgoingMessages> staged = new ArrayList<>();

    @Override
    public synchronized void dispatch(Object domainEvent) {
        if (domainEvent == null) {
            throw new IllegalArgumentException("domainEvent must not be null");
        }
        queue.add(domainEvent);
    }

    @Override
    public void registerSagaType(Class<?> sagaClass) {
        if (sagaClass == null) {
            throw new IllegalArgumentException("sagaClass must not be null");
        }
    }

    @Override
    public Timing timing() {
        return Timing.IN_TRANSACTION;
    }

    @Override
    public boolean supportsCommands() {
        return true;
    }

    @Override
    public synchronized void store(OutgoingMessages batch) {
        staged.add(batch);
    }

    @Override
    public synchronized void committed(OutgoingMessages batch) {
        if (staged.remove(batch)) {
            queue.addAll(batch.events());
            queue.addAll(batch.commands());
        }
    }

    /**
     * Delivers everything queued, including what the deliveries queue in turn.
     *
     * @param reports         receives the report of every delivered event
     * @param commandFailures receives the exception of every outbox command whose handling failed (the transport
     *                        would redeliver it; the fixture does not)
     */
    void drain(SagaRuntime runtime, FixtureCommandInterceptor interceptor, Consumer<HandlingReport> reports,
            Consumer<RuntimeException> commandFailures) {
        int deliveries = 0;
        Object next;
        while ((next = poll()) != null) {
            if (++deliveries > MAX_DELIVERIES) {
                throw new IllegalStateException("more than " + MAX_DELIVERIES + " messages were delivered for one"
                        + " event; do two sagas keep triggering each other?");
            }
            if (next instanceof OutgoingCommand command) {
                interceptor.deliveringOutbox(true);
                try {
                    runtime.commandReceiver().receive(new OutboxCommand(command.commandId(), command.command(),
                            transaction -> { }));
                } catch (RuntimeException failure) {
                    commandFailures.accept(failure);
                } finally {
                    interceptor.deliveringOutbox(false);
                }
            } else {
                reports.accept(runtime.receive(next));
            }
        }
        synchronized (this) {
            staged.clear();
        }
    }

    private synchronized Object poll() {
        return queue.poll();
    }
}
