package io.github.gkosharovdev.sagaforge.storage.postgres;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BillingSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BookShipment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ChargeCard;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.DeliverySaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentAuthorized;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentConfirmed;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.RequestPayment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShipmentBooked;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;

/**
 * SC-001 and SC-002 over PostgreSQL (specs/007 tasks.md T049): the process stops at each point between a unit of
 * work's commit and the message's consumption; a new engine and transport on the same database deliver every committed
 * message, no step runs twice, and nothing from a rolled-back unit of work is ever logged.
 */
@Tag("container")
class TransportFaultInjectionContainerTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private PostgresTransportHarness harness;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.reset();
        harness = new PostgresTransportHarness(TransportHarness.FAST, FulfilmentSaga.class, DeliverySaga.class,
                BillingSaga.class, PaymentService.class, ShippingService.class, CardService.class,
                RequestPayment.class, BookShipment.class, ChargeCard.class);
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private void startOrder() {
        harness.publish(new OrderPlaced("o-1"));
        await(() -> PostgresTransportHarness.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");
        harness.awaitQuiet(WAIT);
    }

    @Test
    @DisplayName("stop after commit, before any delivery: the next process delivers it")
    void stopAfterCommit() {
        harness.publish(new OrderPlaced("o-1"));
        assertThat(harness.messages()).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(MessageStatus.UNCONSUMED));

        harness.restart(Duration.ofMillis(10));

        await(() -> PostgresTransportHarness.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");
        harness.awaitQuiet(WAIT);
        assertThat(harness.payments.requested()).containsExactly("o-1");
    }

    @Test
    @DisplayName("stop after a lease, before delivery: once the dead lease expires, the next process delivers it")
    void stopAfterLease() {
        harness.publish(new OrderPlaced("o-1"));
        harness.log().lease("dead-process", Instant.now(), Duration.ofSeconds(1), 10);

        harness.start();

        await(() -> PostgresTransportHarness.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");
        harness.awaitQuiet(WAIT);
        assertThat(harness.messages()).singleElement().satisfies(m -> assertThat(m.attempts()).isEqualTo(2));
    }

    @Test
    @DisplayName("stop during delivery, before the step committed: the next process runs it once")
    void stopDuringDelivery() {
        harness.start();
        startOrder();
        CountDownLatch crashed = harness.receiver().hold(PaymentConfirmed.class);
        harness.publish(new PaymentConfirmed("o-1"));
        await(() -> harness.messagesOf(PaymentConfirmed.class).stream().anyMatch(m -> m.leasedBy() != null), WAIT,
                "the confirmation to be leased");

        harness.restart(Duration.ofMillis(50));
        crashed.countDown();

        await(() -> PostgresTransportHarness.hasInstance(FulfilmentSaga.class, "COMPLETED"), WAIT, "completion");
        harness.awaitQuiet(WAIT);
        assertThat(harness.shipping.booked()).containsExactly("o-1");
        assertThat(PostgresTransportHarness.instances(DeliverySaga.class)).hasSize(1);
    }

    @Test
    @DisplayName("stop after the step committed, before the consumed mark: the redelivery is a DUPLICATE")
    void stopBeforeConsumedMark() {
        harness.start();
        startOrder();
        PostgresTestSupport.failOnConsume(PaymentAuthorized.class, 1);

        harness.publish(new PaymentAuthorized("o-1"));

        await(() -> harness.receiver().results().stream().anyMatch(r -> r.outcome() == HandlingOutcome.DUPLICATE),
                WAIT, "the duplicate redelivery");
        harness.awaitQuiet(WAIT);
        assertThat(harness.receiver().results().stream().filter(r -> r.outcome() == HandlingOutcome.WAITING)
                .count()).isEqualTo(2);
        assertThat(harness.messagesOf(PaymentAuthorized.class)).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(MessageStatus.CONSUMED));
    }

    @Test
    @DisplayName("outbox command: a handler whose consumed mark fails rolls back and runs again with the same id")
    void outboxCommandAtomicWithConsumption() {
        harness.start();
        PostgresTestSupport.failOnConsume(ChargeCard.class, 1);

        harness.publish(new InvoiceIssued("i-1"));

        await(() -> PostgresTransportHarness.hasInstance(BillingSaga.class, "COMPLETED"), WAIT, "billing to complete");
        harness.awaitQuiet(WAIT);
        assertThat(harness.cards.charges()).hasSize(2);
        assertThat(harness.cards.charges()).extracting(CardService.Charge::commandId).containsOnly(
                harness.cards.charges().get(0).commandId());
        assertThat(harness.messagesOf(ChargeCard.class)).singleElement().satisfies(m -> {
            assertThat(m.status()).isEqualTo(MessageStatus.CONSUMED);
            assertThat(m.attempts()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("SC-002: a unit of work whose commit fails logs nothing")
    void rolledBackLogsNothing() {
        harness.start();
        startOrder();
        PostgresTestSupport.failOn("saga_instance", "UPDATE");
        harness.publish(new PaymentConfirmed("o-1"));
        await(() -> harness.receiver().deliveries().size() >= 2, WAIT, "the confirmation to be handled");

        assertThat(harness.messagesOf(ShipmentBooked.class)).isEmpty();
        assertThat(PostgresTransportHarness.instances(DeliverySaga.class)).isEmpty();
    }
}
