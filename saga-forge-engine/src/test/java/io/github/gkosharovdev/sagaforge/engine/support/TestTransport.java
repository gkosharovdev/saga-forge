package io.github.gkosharovdev.sagaforge.engine.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;

/**
 * Transport stand-in for engine tests: an {@link EventDispatcher} that records what the engine hands it, and delivers
 * events to an {@link EventReceiver}. It uses nothing of the engine but {@code EventReceiver} (SC-007).
 *
 * <p><strong>Test-only scaffolding.</strong> This class lives in {@code src/test} and must never move to
 * {@code src/main}; the production transports are separate modules (Constitution Principle IV).</p>
 *
 * <ul>
 *   <li>{@link #connect(EventReceiver)} sets the receiver; {@link #deliver(Object)} calls its {@code receive} and
 *       records the {@link HandlingReport}.</li>
 *   <li>After {@link #loopBack()}, every dispatched event is delivered back into the receiver synchronously, from
 *       inside {@code dispatch}, as a synchronous in-memory transport would.</li>
 * </ul>
 *
 * <p>Thread-safe.</p>
 */
public final class TestTransport implements EventDispatcher {

    private final List<Object> dispatched = new CopyOnWriteArrayList<>();
    private final List<Class<?>> registeredSagaTypes = new CopyOnWriteArrayList<>();
    private final List<HandlingReport> reports = new CopyOnWriteArrayList<>();
    private volatile EventReceiver receiver;
    private volatile boolean loopingBack;

    /**
     * @param receiver where {@link #deliver(Object)} hands events; must not be {@code null}
     * @return this transport
     * @throws IllegalArgumentException if {@code receiver} is {@code null}
     */
    public TestTransport connect(EventReceiver receiver) {
        if (receiver == null) {
            throw new IllegalArgumentException("receiver must not be null");
        }
        this.receiver = receiver;
        return this;
    }

    /**
     * Makes every later {@link #dispatch(Object)} deliver the event back into the receiver synchronously.
     *
     * @return this transport
     */
    public TestTransport loopBack() {
        loopingBack = true;
        return this;
    }

    /**
     * Delivers an event to the connected receiver and records its report.
     *
     * @param event the domain event
     * @return the receiver's report
     * @throws IllegalStateException if no receiver is connected
     */
    public HandlingReport deliver(Object event) {
        EventReceiver connected = receiver;
        if (connected == null) {
            throw new IllegalStateException("no EventReceiver connected");
        }
        HandlingReport report = connected.receive(event);
        reports.add(report);
        return report;
    }

    @Override
    public void dispatch(Object domainEvent) {
        if (domainEvent == null) {
            throw new IllegalArgumentException("domainEvent must not be null");
        }
        dispatched.add(domainEvent);
        if (loopingBack) {
            deliver(domainEvent);
        }
    }

    @Override
    public void registerSagaType(Class<?> sagaClass) {
        if (sagaClass == null) {
            throw new IllegalArgumentException("sagaClass must not be null");
        }
        registeredSagaTypes.add(sagaClass);
    }

    /** @return the reports of every delivery that returned, in order; unmodifiable */
    public List<HandlingReport> reports() {
        return List.copyOf(reports);
    }

    /** @return every dispatched event, in order; unmodifiable */
    public List<Object> dispatched() {
        return List.copyOf(dispatched);
    }

    /** @return every registered saga type, in registration order; unmodifiable */
    public List<Class<?>> registeredSagaTypes() {
        return List.copyOf(registeredSagaTypes);
    }
}
