package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ItemsReserved;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentScheduled;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox.Timing;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ImmediateDispatchOutbox}: order, every event attempted after a failure, first failure thrown with the rest
 * suppressed (specs/004-saga-engine tasks.md T044, FR-030).
 */
class ImmediateDispatchOutboxTest {

    private static final ItemsReserved RESERVED = new ItemsReserved("reservation-1", "order-1", 3);
    private static final InvoiceIssued INVOICED = new InvoiceIssued("invoice-1", "order-1", 250L);
    private static final ShipmentScheduled SHIPPED = new ShipmentScheduled("shipment-1", "order-1", "invoice-1");

    private static OutgoingMessages batch(Object... events) {
        return new OutgoingMessages("uow-1", "com.example.Saga", "saga-1", List.of(events));
    }

    /** Records every attempt and fails for the events mapped to a failure. */
    private static final class FailingDispatcher implements EventDispatcher {

        private final Map<Object, RuntimeException> failures;
        private final List<Object> attempted = new ArrayList<>();

        private FailingDispatcher(Map<Object, RuntimeException> failures) {
            this.failures = failures;
        }

        @Override
        public void dispatch(Object domainEvent) {
            attempted.add(domainEvent);
            RuntimeException failure = failures.get(domainEvent);
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void registerSagaType(Class<?> sagaClass) {
        }
    }

    @Test
    @DisplayName("its timing is AFTER_COMMIT")
    void timingIsAfterCommit() {
        assertThat(new ImmediateDispatchOutbox(new RecordingEventDispatcher()).timing())
                .isEqualTo(Timing.AFTER_COMMIT);
    }

    @Test
    @DisplayName("every event is dispatched, in batch order")
    void dispatchesInOrder() {
        RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();

        new ImmediateDispatchOutbox(dispatcher).store(batch(RESERVED, INVOICED, SHIPPED));

        assertThat(dispatcher.dispatched()).containsExactly(RESERVED, INVOICED, SHIPPED);
    }

    @Test
    @DisplayName("a failing dispatch does not stop the rest; the first failure is thrown with the rest suppressed")
    void everyEventAttemptedAfterFailure() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        FailingDispatcher dispatcher = new FailingDispatcher(Map.of(RESERVED, first, SHIPPED, second));

        assertThatThrownBy(() -> new ImmediateDispatchOutbox(dispatcher).store(batch(RESERVED, INVOICED, SHIPPED)))
                .isSameAs(first)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(second));
        assertThat(dispatcher.attempted).containsExactly(RESERVED, INVOICED, SHIPPED);
    }

    @Test
    @DisplayName("a single failure is thrown with nothing suppressed")
    void singleFailure() {
        IllegalStateException failure = new IllegalStateException("only");
        FailingDispatcher dispatcher = new FailingDispatcher(Map.of(INVOICED, failure));

        assertThatThrownBy(() -> new ImmediateDispatchOutbox(dispatcher).store(batch(RESERVED, INVOICED, SHIPPED)))
                .isSameAs(failure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());
        assertThat(dispatcher.attempted).containsExactly(RESERVED, INVOICED, SHIPPED);
    }

    @Test
    @DisplayName("the same exception thrown twice is not suppressed into itself")
    void sameExceptionNotSelfSuppressed() {
        IllegalStateException shared = new IllegalStateException("shared");
        FailingDispatcher dispatcher = new FailingDispatcher(Map.of(RESERVED, shared, SHIPPED, shared));

        assertThatThrownBy(() -> new ImmediateDispatchOutbox(dispatcher).store(batch(RESERVED, SHIPPED)))
                .isSameAs(shared)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());
    }

    @Test
    @DisplayName("a null dispatcher or batch is rejected")
    void nullsRejected() {
        assertThatThrownBy(() -> new ImmediateDispatchOutbox(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImmediateDispatchOutbox(new RecordingEventDispatcher()).store(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
