package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRequestedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentConfirmedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentDeclinedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentInitiatedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentService;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.AddNote;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.AuditSaga;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.Case;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseNoted;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.CaseOpened;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.NoteService;
import io.github.gkosharovdev.sagaforge.engine.fixtures.fanout.ReviewSaga;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary.TransactionEvent;
import io.github.gkosharovdev.sagaforge.engine.support.TestTransport;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A transport that only calls {@code EventReceiver.receive} and only receives dispatches runs sagas end to end, looping
 * dispatched events back from inside the after-commit outbox (specs/004-saga-engine tasks.md T087, User Story 5
 * scenarios 1 and 5, SC-007 engine half, quickstart.md scenario 14).
 *
 * <p>The test drives the engine through {@link TestTransport} and {@link EventReceiver} only; the stores are test
 * doubles it reads to check the outcome.</p>
 */
class TestTransportEndToEndTest {

    private static final String BOOKING = "booking-1";
    private static final String INVENTORY = "inventory-1";

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final TestTransport transport = new TestTransport();

    @BeforeEach
    void resetFaults() {
        Booking.resetFaults();
        ReviewSaga.failOnClose(false);
    }

    private EventReceiver engineOver(EventDispatcher dispatcher, MapHandlerInstanceProvider instances,
            Class<?>... registered) {
        SagaEngine engine = SagaEngine.builder()
                .register(registered)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(dispatcher)
                .handlerInstances(instances)
                .build();
        engine.start();
        return engine;
    }

    private void connectBookingEngine() {
        InventoryRepository inventories = new InventoryRepository();
        inventories.add(new Inventory(INVENTORY));
        transport.connect(engineOver(transport, new MapHandlerInstanceProvider().register(new PaymentService())
                        .register(inventories).register(new BookingRepository()),
                CreateBookingSaga.class, PaymentService.class, Inventory.class, InventoryRepository.class,
                Booking.class, BookingRepository.class)).loopBack();
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    private SagaHandlingResult requestBooking() {
        return onlyResult(transport.deliver(new BookingRequestedEvent(BOOKING, INVENTORY, LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 4), 300L)));
    }

    @Test
    @DisplayName("CreateBookingSaga reaches onBookingConfirmed through the transport alone")
    void bookingConfirmed() {
        connectBookingEngine();
        assertThat(transport.registeredSagaTypes()).containsExactly(CreateBookingSaga.class);

        SagaHandlingResult first = requestBooking();
        // PaymentInitiatedEvent was dispatched after commit and looped back: no saga reacts to it.
        assertThat(transport.dispatched()).singleElement().isInstanceOf(PaymentInitiatedEvent.class);
        assertThat(transport.reports().get(0).ignored()).isTrue();
        assertThat(first.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(first.started()).isTrue();

        SagaHandlingResult second = onlyResult(transport.deliver(
                new PaymentConfirmedEvent(PaymentService.paymentIdFor(BOOKING), BOOKING)));

        assertThat(second.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(second.outcomeStep()).isEqualTo(CreateBookingSaga.OUTCOME_CONFIRMED);
        assertThat(second.sagaId()).isEqualTo(first.sagaId());
        assertThat(storage.committed().findById(first.sagaId())).hasValueSatisfying(
                instance -> assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED));
    }

    @Test
    @DisplayName("CreateBookingSaga reaches onPaymentDeclined through the transport alone")
    void paymentDeclined() {
        connectBookingEngine();
        SagaHandlingResult first = requestBooking();

        SagaHandlingResult second = onlyResult(transport.deliver(
                new PaymentDeclinedEvent(PaymentService.paymentIdFor(BOOKING), BOOKING, "card expired")));

        assertThat(second.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(second.outcomeStep()).isEqualTo(CreateBookingSaga.OUTCOME_DECLINED);
        assertThat(second.sagaId()).isEqualTo(first.sagaId());
    }

    @Test
    @DisplayName("US5-5: AuditSaga's outgoing CaseNoted, looped back, starts ReviewSaga after AuditSaga committed")
    void loopedBackEventRunsAsNewUnitOfWork() {
        List<String> atLoopBack = new ArrayList<>();
        EventDispatcher observing = new EventDispatcher() {
            @Override
            public void dispatch(Object domainEvent) {
                atLoopBack.add("transaction active: " + boundary.isActive());
                storage.committed().all().forEach(instance -> atLoopBack.add(
                        instance.sagaType().substring(instance.sagaType().lastIndexOf('.') + 1) + ": "
                                + instance.status()));
                transport.dispatch(domainEvent);
            }

            @Override
            public void registerSagaType(Class<?> sagaClass) {
                transport.registerSagaType(sagaClass);
            }
        };
        transport.connect(engineOver(observing, new MapHandlerInstanceProvider().register(new NoteService()),
                AuditSaga.class, ReviewSaga.class, NoteService.class, Case.class)).loopBack();

        SagaHandlingResult audit = onlyResult(transport.deliver(new CaseOpened("case-1", AddNote.Mode.NOTE)));

        assertThat(atLoopBack).containsExactly("transaction active: false", "AuditSaga: RUNNING");
        assertThat(transport.dispatched()).containsExactly(new CaseNoted("case-1"));
        SagaHandlingResult review = onlyResult(transport.reports().get(0));
        assertThat(review.sagaType()).isEqualTo(ReviewSaga.class.getName());
        assertThat(review.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(review.started()).isTrue();
        assertThat(audit.sagaType()).isEqualTo(AuditSaga.class.getName());
        assertThat(audit.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(audit.outboxFailures()).isEmpty();
        // Four transactions one after the other on this thread, none nested: AuditSaga's creation and unit of work,
        // then ReviewSaga's creation and unit of work.
        assertThat(boundary.historyOf(Thread.currentThread())).containsExactly(
                TransactionEvent.BEGIN, TransactionEvent.COMMIT, TransactionEvent.BEGIN, TransactionEvent.COMMIT,
                TransactionEvent.BEGIN, TransactionEvent.COMMIT, TransactionEvent.BEGIN, TransactionEvent.COMMIT);
    }
}
