package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;

/**
 * An {@link EventReceiver} that delegates (to the engine, or to a test function) and records every delivered event,
 * every report, the delivering thread, and every failure. Thread-safe.
 */
public final class RecordingReceiver implements EventReceiver {

    /** One delivery. */
    public record Delivery(Object event, HandlingReport report, Throwable failure, String thread) {
    }

    private final Function<Object, HandlingReport> delegate;
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final java.util.Map<Class<?>, java.util.concurrent.CountDownLatch> holds =
            new java.util.concurrent.ConcurrentHashMap<>();

    public RecordingReceiver(Function<Object, HandlingReport> delegate) {
        this.delegate = delegate;
    }

    /** @return a receiver that accepts everything and reports it as ignored */
    public static RecordingReceiver accepting() {
        return new RecordingReceiver(event -> new HandlingReport(event, List.of()));
    }

    @Override
    public HandlingReport receive(Object domainEvent) {
        java.util.concurrent.CountDownLatch hold = holds.get(domainEvent.getClass());
        if (hold != null) {
            try {
                hold.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("process crashed while handling " + domainEvent);
        }
        try {
            HandlingReport report = delegate.apply(domainEvent);
            deliveries.add(new Delivery(domainEvent, report, null, Thread.currentThread().getName()));
            return report;
        } catch (RuntimeException | Error failure) {
            deliveries.add(new Delivery(domainEvent, null, failure, Thread.currentThread().getName()));
            throw failure;
        }
    }

    /**
     * Makes deliveries of {@code type} block until the returned latch is released, then throw without reaching the
     * delegate, as a process that crashed mid-delivery would never complete it.
     */
    public java.util.concurrent.CountDownLatch hold(Class<?> type) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        holds.put(type, latch);
        return latch;
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    /** @return the events delivered without failure, in delivery order */
    public List<Object> delivered() {
        return deliveries.stream().filter(d -> d.failure() == null).map(Delivery::event).toList();
    }

    /** @return every saga result of every successful delivery */
    public List<SagaHandlingResult> results() {
        return deliveries.stream().filter(d -> d.report() != null).flatMap(d -> d.report().results().stream())
                .toList();
    }
}
