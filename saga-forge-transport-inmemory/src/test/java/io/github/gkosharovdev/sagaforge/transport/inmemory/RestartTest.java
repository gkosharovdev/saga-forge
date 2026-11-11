package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BookShipment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.DeliverySaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentAuthorized;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentConfirmed;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.RequestPayment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;

/**
 * User Story 2 (spec.md, tasks.md T038): unconsumed messages survive a restart. A restart is a new engine and
 * transport over the same saga storage and message store.
 */
class RestartTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final TransportHarness harness = new TransportHarness(TransportHarness.FAST, FulfilmentSaga.class,
            DeliverySaga.class, PaymentService.class, ShippingService.class, RequestPayment.class,
            BookShipment.class).start();

    @AfterEach
    void stop() {
        harness.close();
    }

    private void startOrder() {
        harness.publish(new OrderPlaced("o-1"));
        await(() -> harness.instance(FulfilmentSaga.class, SagaStatus.RUNNING).isPresent(), WAIT, "the saga to start");
        harness.awaitQuiet(WAIT);
    }

    @Test
    @DisplayName("scenario 1: messages committed but not delivered before the stop are delivered after restart")
    void undeliveredMessagesDeliveredAfterRestart() {
        harness.log.pauseLeasing(true);
        harness.publish(new OrderPlaced("o-1"));
        harness.publish(new OrderPlaced("o-2"));
        assertThat(harness.log.all()).hasSize(2)
                .allSatisfy(m -> assertThat(m.status()).isEqualTo(MessageStatus.UNCONSUMED));

        harness.restart();
        harness.log.pauseLeasing(false);

        harness.awaitQuiet(WAIT);
        assertThat(harness.instancesOf(FulfilmentSaga.class)).hasSize(2);
        assertThat(harness.payments.requested()).containsExactlyInAnyOrder("o-1", "o-2");
    }

    @Test
    @DisplayName("scenario 2: an event whose handling never committed is delivered again after the restart")
    void interruptedDeliveryRedelivered() {
        startOrder();
        CountDownLatch crashed = harness.receiver().hold(PaymentConfirmed.class);
        harness.publish(new PaymentConfirmed("o-1"));
        await(() -> harness.messagesOf(PaymentConfirmed.class).stream().anyMatch(m -> m.leasedBy() != null), WAIT,
                "the confirmation to be leased");

        harness.restartAfterStop(Duration.ofMillis(50));
        crashed.countDown();

        await(() -> harness.instance(FulfilmentSaga.class, SagaStatus.COMPLETED).isPresent(), WAIT,
                "the saga to complete");
        harness.awaitQuiet(WAIT);
        assertThat(harness.shipping.booked()).containsExactly("o-1");
        assertThat(harness.instancesOf(DeliverySaga.class)).hasSize(1);
    }

    @Test
    @DisplayName("scenario 3: an event handled before the stop but not marked consumed is reported DUPLICATE")
    void handledButUnmarkedIsDuplicate() {
        startOrder();
        harness.log.pauseLeasing(true);
        harness.publish(new PaymentAuthorized("o-1"));
        harness.log.failNext(InMemoryMessageStore.Operation.MARK_CONSUMED,
                new IllegalStateException("process stopped before the consumed mark"));
        harness.log.pauseLeasing(false);

        await(() -> harness.receiver().results().stream()
                .anyMatch(r -> r.outcome() == HandlingOutcome.DUPLICATE), WAIT, "the duplicate redelivery");
        harness.awaitQuiet(WAIT);
        assertThat(harness.receiver().results().stream()
                .filter(r -> r.outcome() == HandlingOutcome.WAITING).count()).isEqualTo(2);
        assertThat(harness.messagesOf(PaymentAuthorized.class)).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(MessageStatus.CONSUMED));
    }

    @Test
    @DisplayName("scenario 4: with nothing unconsumed, a restart delivers nothing")
    void nothingToDeliver() throws InterruptedException {
        startOrder();
        int before = harness.receiver().deliveries().size();

        harness.restart();
        Thread.sleep(200);

        assertThat(harness.receiver().deliveries()).isEmpty();
        assertThat(before).isEqualTo(1);
        assertThat(harness.payments.requested()).containsExactly("o-1");
    }
}
