package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * {@link EventPublisher} of the saga test harness (contracts/event-publisher.md, spec FR-016 / FR-017), obtained
 * from {@link SagaTestHarness#eventPublisher()}.
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <h2>{@link #publish(Object)}</h2>
 * <ul>
 *   <li>{@link IllegalArgumentException} for a {@code null} or non-{@code @DomainEvent} event.</li>
 *   <li><b>Inside a unit of work</b> (a recorder is bound on the calling thread, see
 *       {@link DomainEventScope#current()}): exactly {@link DomainEvents#record(Object)}. In a harness unit of work
 *       the event is delivered in-process if it triggers a step of the owning instance, otherwise dispatched after
 *       commit and discarded on rollback. Nothing is added to {@link #published()}.</li>
 *   <li><b>Outside any unit of work</b>: the event is handed to the harness' {@link RecordingEventDispatcher}
 *       immediately (it appears in {@link SagaTestHarness#dispatcher()}{@code .dispatched()}), and then, standing in
 *       for the transport delivering it, passed once to {@link SagaTestHarness#deliverAll(Object)}. This is how a
 *       test starts a saga from application code. The results are available from {@link #lastResults()}.</li>
 * </ul>
 *
 * <h2>What is delivered back into the harness</h2>
 * <p>Only the event passed to {@code publish} outside a unit of work, exactly once. Events the resulting units of
 * work dispatch after commit go to the dispatcher only, unless the harness was built with
 * {@link SagaTestHarness.Builder#redeliverDispatched()}; even then the published event itself is not delivered a
 * second time. Do not also install a {@link RecordingEventDispatcher#onDispatch} listener that delivers events:
 * it would deliver the published event twice.</p>
 *
 * <p>Calling {@code publish} from another thread while a harness unit of work runs fails with
 * {@link IllegalStateException} (the harness runs one unit of work at a time). Exceptions from delivery (for
 * example a missing association property) propagate to the caller. Not thread-safe.</p>
 */
public final class HarnessEventPublisher implements EventPublisher {

    private final Function<Object, List<UnitOfWorkResult>> publishImmediately;
    private final List<Object> published = new ArrayList<>();
    private List<UnitOfWorkResult> lastResults = List.of();

    /**
     * @param publishImmediately dispatches the event and delivers it into the harness, returning the results
     */
    HarnessEventPublisher(Function<Object, List<UnitOfWorkResult>> publishImmediately) {
        this.publishImmediately = publishImmediately;
    }

    @Override
    public void publish(Object domainEvent) {
        if (domainEvent == null) {
            throw new IllegalArgumentException("domainEvent must not be null");
        }
        if (!domainEvent.getClass().isAnnotationPresent(DomainEvent.class)) {
            throw new IllegalArgumentException(domainEvent.getClass().getName() + " is not annotated @DomainEvent");
        }
        if (DomainEventScope.current().isPresent()) {
            DomainEvents.record(domainEvent);
            return;
        }
        published.add(domainEvent);
        lastResults = List.of();
        lastResults = publishImmediately.apply(domainEvent);
    }

    /** Events published outside any unit of work, in publish order. */
    public List<Object> published() {
        return List.copyOf(published);
    }

    /**
     * Results of delivering the last event published outside a unit of work ({@code deliverAll} semantics: one
     * per affected saga type / instance, or a single {@code IGNORED} result); empty before the first such publish.
     */
    public List<UnitOfWorkResult> lastResults() {
        return lastResults;
    }
}
