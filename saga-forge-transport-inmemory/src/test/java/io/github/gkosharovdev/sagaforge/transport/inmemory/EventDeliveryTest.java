package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.log.LoggedMessage;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BookShipment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.DeliverySaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Order;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Payment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentConfirmed;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.RequestPayment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Shipment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShipmentBooked;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.Unrouted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageLog;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;

/**
 * User Story 1 (specs/007-in-memory-transport spec.md, tasks.md T030): events between modules are delivered in memory,
 * after commit, from the log.
 */
class EventDeliveryTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final TransportHarness harness = new TransportHarness(TransportHarness.FAST, FulfilmentSaga.class,
            DeliverySaga.class, PaymentService.class, ShippingService.class, Order.class, Payment.class,
            Shipment.class, RequestPayment.class, BookShipment.class).start();

    @AfterEach
    void stop() {
        harness.close();
    }

    @Test
    @DisplayName("scenario 1: an event published outside a unit of work starts a saga after it is logged")
    void publishedEventStartsSaga() {
        harness.publish(new OrderPlaced("o-1"));

        await(() -> harness.instance(FulfilmentSaga.class, SagaStatus.RUNNING).isPresent(), WAIT, "the saga to start");
        assertThat(harness.payments.requested()).containsExactly("o-1");
        harness.awaitQuiet(WAIT);
        assertThat(harness.messagesOf(OrderPlaced.class)).singleElement().satisfies(m -> {
            assertThat(m.status()).isEqualTo(MessageStatus.CONSUMED);
            assertThat(m.kind()).isEqualTo(MessageKind.EVENT);
            assertThat(m.sourceSagaId()).isNull();
            assertThat(m.orderingKey()).isEqualTo(Order.class.getName() + "#o-1");
        });
    }

    @Test
    @DisplayName("scenario 2: an event recorded in a committed unit of work reaches another saga after commit")
    void committedEventReachesOtherSaga() {
        harness.publish(new OrderPlaced("o-1"));
        await(() -> harness.instance(FulfilmentSaga.class, SagaStatus.RUNNING).isPresent(), WAIT, "the saga to start");

        harness.publish(new PaymentConfirmed("o-1"));

        await(() -> harness.instance(DeliverySaga.class, SagaStatus.RUNNING).isPresent(), WAIT,
                "the delivery saga to start");
        assertThat(harness.instance(FulfilmentSaga.class, SagaStatus.COMPLETED)).isPresent();
        harness.awaitQuiet(WAIT);
        LoggedMessage booked = harness.messagesOf(ShipmentBooked.class).get(0);
        assertThat(booked.sourceSagaType()).isEqualTo(FulfilmentSaga.class.getName());
        assertThat(booked.status()).isEqualTo(MessageStatus.CONSUMED);
        assertThat(harness.log.all()).allSatisfy(m -> assertThat(m.status()).isEqualTo(MessageStatus.CONSUMED));
    }

    @Test
    @DisplayName("scenario 3: a unit of work that rolls back logs and delivers nothing")
    void rolledBackDeliversNothing() {
        harness.publish(new OrderPlaced("o-1"));
        await(() -> harness.instance(FulfilmentSaga.class, SagaStatus.RUNNING).isPresent(), WAIT, "the saga to start");
        harness.awaitQuiet(WAIT);
        harness.log.failAppendOf(ShipmentBooked.class, new IllegalStateException("outbox down"));

        harness.publish(new PaymentConfirmed("o-1"));

        await(() -> harness.receiver().deliveries().size() >= 2, WAIT, "the confirmation to be handled");
        assertThat(harness.receiver().results()).anySatisfy(r -> assertThat(r.outcome())
                .isEqualTo(HandlingOutcome.FAILED));
        assertThat(harness.messagesOf(ShipmentBooked.class)).isEmpty();
        assertThat(harness.instancesOf(DeliverySaga.class)).isEmpty();
        // FR-017: a FAILED saga is an outcome, not a delivery failure - the confirmation is consumed, not retried.
        await(() -> harness.messagesOf(PaymentConfirmed.class).get(0).status() == MessageStatus.CONSUMED, WAIT,
                "the confirmation to be consumed");
        assertThat(harness.messagesOf(PaymentConfirmed.class).get(0).attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("scenario 4: an event no saga reacts to is consumed once and never delivered again")
    void unroutedEventConsumedOnce() throws InterruptedException {
        harness.publish(new Unrouted("o-9"));

        harness.awaitQuiet(WAIT);
        Thread.sleep(200);
        assertThat(harness.receiver().deliveries()).singleElement()
                .satisfies(d -> assertThat(d.report().ignored()).isTrue());
    }

    @Test
    @DisplayName("FR-009: delivery runs on a transport thread, never on the publishing thread")
    void deliveredOnTransportThread() {
        harness.publish(new OrderPlaced("o-1"));

        await(() -> !harness.receiver().deliveries().isEmpty(), WAIT, "a delivery");
        assertThat(harness.receiver().deliveries().get(0).thread()).startsWith("saga-forge-transport-")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("FR-007: events of one aggregate are delivered in publication order")
    void orderPerAggregate() {
        for (int i = 0; i < 20; i++) {
            harness.publish(new Unrouted("same"));
            harness.publish(new Unrouted("other-" + i));
        }

        harness.awaitQuiet(WAIT);
        List<Long> sameOrder = harness.receiver().deliveries().stream()
                .filter(d -> ((Unrouted) d.event()).orderId().equals("same"))
                .map(d -> harness.log.all().stream().filter(m -> m.payload() == d.event()).findFirst().orElseThrow()
                        .position())
                .toList();
        assertThat(sameOrder).hasSize(20).isSorted();
    }

    @Test
    @DisplayName("FR-002: a failed append reaches the publisher and nothing is delivered")
    void failedAppendReachesCaller() {
        IllegalStateException failure = new IllegalStateException("log down");
        harness.log.failNext(InMemoryMessageLog.Operation.APPEND, failure);

        assertThatThrownBy(() -> harness.publish(new OrderPlaced("o-1"))).isSameAs(failure);
        assertThat(harness.log.all()).isEmpty();
        assertThat(harness.receiver().deliveries()).isEmpty();
    }
}
