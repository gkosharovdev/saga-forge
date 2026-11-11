package io.github.gkosharovdev.sagaforge.transport.inmemory.support;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.MessageStoreQueries;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BillingSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BookShipment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ChargeCard;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.DeliverySaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.FulfilmentSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.OrderPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentConfirmed;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.RequestPayment;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;

/**
 * The User Story 1 to 4 acceptance scenarios, run unchanged in every setup (specs/007-in-memory-transport SC-008,
 * tasks.md T072): plain Java with Saga Forge's own log, Spring with its own log, and Spring with Spring Modulith interop.
 */
public abstract class TransportAcceptanceSuite {

    /** The model every setup registers. */
    public static final Class<?>[] MODEL = { FulfilmentSaga.class, DeliverySaga.class, BillingSaga.class,
            PaymentService.class, ShippingService.class, CardService.class, RequestPayment.class, BookShipment.class,
            ChargeCard.class };

    protected static final Duration WAIT = Duration.ofSeconds(10);

    /** One running setup: engine, transport and log, restartable over the same data. */
    public interface Setup extends AutoCloseable {

        void publish(Object event);

        /** @return whether an instance of {@code sagaType} has {@code status} (a {@code SagaStatus} name) */
        boolean hasInstance(Class<?> sagaType, String status);

        int instanceCount(Class<?> sagaType);

        /** Stops this process (no delivery in flight) and starts a new one over the same data. */
        void restart();

        /** Makes the next delivery of {@code type} fail {@code times} times before the receiver is reached. */
        void failDeliveriesOf(Class<?> type, int times);

        MessageStoreQueries queries();

        PaymentService payments();

        CardService cards();

        @Override
        void close();
    }

    /** @return a fresh, started setup with empty data */
    protected abstract Setup newSetup();

    protected Setup setup;

    @BeforeEach
    void startSetup() {
        setup = newSetup();
    }

    @AfterEach
    void closeSetup() {
        setup.close();
    }

    protected List<StoredMessage> messagesOf(Class<?> type) {
        return setup.queries().find(MessageQuery.builder().payloadType(type.getName()).build());
    }

    protected void awaitQuiet() {
        await(() -> {
            List<StoredMessage> all = setup.queries().find(MessageQuery.builder().limit(MessageQuery.MAX_LIMIT)
                    .build());
            return !all.isEmpty() && all.stream().allMatch(m -> m.status() != MessageStatus.UNCONSUMED);
        }, WAIT, "the store to become quiet");
    }

    @Test
    @DisplayName("US1: an event published outside a unit of work starts a saga; a committed event reaches another")
    void eventsBetweenModules() {
        setup.publish(new OrderPlaced("o-1"));
        await(() -> setup.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");

        setup.publish(new PaymentConfirmed("o-1"));

        await(() -> setup.hasInstance(DeliverySaga.class, "RUNNING"), WAIT, "the delivery saga to start");
        assertThat(setup.hasInstance(FulfilmentSaga.class, "COMPLETED")).isTrue();
        awaitQuiet();
        assertThat(setup.payments().requested()).containsExactly("o-1");
    }

    @Test
    @DisplayName("US2: a message whose delivery failed before a restart is delivered after it, exactly once")
    void survivesRestart() {
        setup.failDeliveriesOf(OrderPlaced.class, 1);
        setup.publish(new OrderPlaced("o-1"));
        await(() -> messagesOf(OrderPlaced.class).stream().anyMatch(m -> m.lastFailure() != null), WAIT,
                "the first attempt to fail");

        setup.restart();

        await(() -> setup.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");
        awaitQuiet();
        assertThat(setup.instanceCount(FulfilmentSaga.class)).isEqualTo(1);
        assertThat(setup.payments().requested()).containsExactly("o-1");
    }

    @Test
    @DisplayName("US3: a outbox command is handled after its step commits, with the identity assigned at send time")
    void outboxCommand() {
        setup.publish(new InvoiceIssued("i-1"));

        await(() -> setup.hasInstance(BillingSaga.class, "COMPLETED"), WAIT, "billing to complete");
        awaitQuiet();
        assertThat(setup.cards().charges()).singleElement().satisfies(charge -> {
            assertThat(charge.commandId().stepName()).isEqualTo(BillingSaga.START_STEP);
            assertThat(charge.commandId().value()).isEqualTo(messagesOf(ChargeCard.class).get(0).messageId());
        });
    }

    @Test
    @DisplayName("US4: a message that keeps failing is set aside, reported, and delivered again on request")
    void operatorView() {
        setup.failDeliveriesOf(OrderPlaced.class, 1_000);
        setup.publish(new OrderPlaced("o-1"));
        await(() -> messagesOf(OrderPlaced.class).stream().anyMatch(m -> m.status() == MessageStatus.SET_ASIDE),
                WAIT, "the message to be set aside");
        StoredMessage setAside = messagesOf(OrderPlaced.class).get(0);
        assertThat(setAside.lastFailure()).isNotNull();
        assertThat(setAside.setAsideReason()).isEqualTo("MAX_ATTEMPTS");

        setup.failDeliveriesOf(OrderPlaced.class, 0);
        assertThat(setup.queries().redeliver(setAside.messageId())).isTrue();

        await(() -> setup.hasInstance(FulfilmentSaga.class, "RUNNING"), WAIT, "the saga to start");
        awaitQuiet();
    }
}
