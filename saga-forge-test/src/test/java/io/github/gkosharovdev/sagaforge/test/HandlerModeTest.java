package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.DispatchSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.LabelParcel;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.Notifier;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.NotifyCustomer;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.Parcel;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.ParcelPacked;
import io.github.gkosharovdev.sagaforge.test.fixtures.Dispatch.ParcelStore;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.CardDeclined;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentDesk;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.RefundPayment;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ReserveStock;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopOrder;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockReserved;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.TakePayment;

/** User Story 4: a saga with its command handlers and aggregates (specs/009-saga-test-fixture tasks.md T042). */
class HandlerModeTest {

    @Test
    @DisplayName("scenarios 1 and 2: handlers and a seeded aggregate drive the saga to COMPLETED in one call")
    void wholeFlow() {
        PaymentDesk desk = new PaymentDesk();
        SagaFixture fixture = SagaFixture.of(ShopSaga.class, ShopOrder.class).handlers(desk).build();

        fixture.givenAggregates(ShopOrder.create("o-5", true))
                .when(new OrderPlaced("o-5", "10"))
                .expectCommands(new TakePayment("o-5", "10"), new ReserveStock("o-5"))
                .expectCompletedStep(Shop.PLACED)
                .expectCompletedStep(Shop.PAID)
                .expectEndedWith(Shop.RESERVED);

        assertThat(fixture.aggregate(ShopOrder.class, "o-5")).hasValueSatisfying(order ->
                assertThat(order.status()).isEqualTo("RESERVED"));
        assertThat(fixture.commands()).allSatisfy(command -> assertThat(command.handled()).isTrue());
        assertThat(desk.payments()).hasSize(1);
    }

    @Test
    @DisplayName("an out-of-stock aggregate compensates through the real refund handler")
    void outOfStock() {
        PaymentDesk desk = new PaymentDesk();
        SagaFixture fixture = SagaFixture.of(ShopSaga.class, ShopOrder.class).handlers(desk).build();

        fixture.givenAggregates(ShopOrder.create("o-6", false))
                .when(new OrderPlaced("o-6", "10"))
                .expectCompensations(Shop.REFUND)
                .expectCompensatedAfter(Shop.MISSING);

        assertThat(desk.refunds()).containsExactly(new RefundPayment("o-6", "pay-o-6", "10"));
        assertThat(fixture.aggregate(ShopOrder.class, "o-6").orElseThrow().status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("a rolled-back unit of work leaves the stored aggregate as it was; handlers get a copy")
    void rollbackRestoresAggregates() {
        FixtureTransactions transactions = new FixtureTransactions();
        AggregateStore store = new AggregateStore(java.util.Set.of(ShopOrder.class));
        transactions.participant(store);
        ShopOrder seeded = ShopOrder.create("o-9", true);
        store.seed(seeded);
        ShopOrder stored = store.find(ShopOrder.class, "o-9").orElseThrow();

        transactions.begin();
        Object loaded = store.load(ShopOrder.class, "o-9").orElseThrow();
        store.save(ShopOrder.create("o-9", false));
        transactions.current().orElseThrow().rollback();

        assertThat(loaded).isNotSameAs(stored);
        assertThat(store.find(ShopOrder.class, "o-9")).containsSame(stored);

        transactions.begin();
        store.save(ShopOrder.create("o-9", false));
        transactions.current().orElseThrow().commit();
        assertThat(store.find(ShopOrder.class, "o-9").orElseThrow()).isNotSameAs(stored);
    }

    @Test
    @DisplayName("scenario 3: a command whose handler is not registered is recorded and answered by the test")
    void unregisteredHandlerIsRecorded() {
        PaymentDesk desk = new PaymentDesk();
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).handlers(desk).build();

        Then then = fixture.when(new OrderPlaced("o-5", "10"))
                .expectCommands(new TakePayment("o-5", "10"), new ReserveStock("o-5"))
                .expectStatus(SagaStatus.RUNNING);

        assertThat(then.result().commands()).extracting(RecordedCommand::handled).containsExactly(true, false);
        fixture.when(new StockReserved("o-5")).expectEndedWith(Shop.RESERVED);
    }

    @Test
    @DisplayName("scenario 4: an outbox command is handled after the commit; its failure does not roll the step back")
    void outboxCommands() {
        ParcelStore parcels = new ParcelStore().with(new Parcel("p-1"));
        Notifier notifier = new Notifier();
        SagaFixture fixture = SagaFixture.of(DispatchSaga.class, Parcel.class).handlers(parcels, notifier).build();

        Then then = fixture.when(new ParcelPacked("p-1"))
                .expectCommands(new LabelParcel("p-1"), new NotifyCustomer("p-1"))
                .expectEndedWith("notified");

        assertThat(then.result().commands()).extracting(RecordedCommand::delivery).containsExactly(
                RecordedCommand.Delivery.IN_UNIT_OF_WORK, RecordedCommand.Delivery.OUTBOX);
        assertThat(parcels.parcel("p-1").labelled()).as("the client loader and saver win").isTrue();
        assertThat(fixture.aggregate(Parcel.class, "p-1")).isEmpty();

        SagaFixture failing = SagaFixture.of(DispatchSaga.class, Parcel.class)
                .handlers(new ParcelStore().with(new Parcel("p-2")), new Notifier())
                .failCommand(NotifyCustomer.class, () -> new IllegalStateException("mail down")).build();
        Then failed = failing.when(new ParcelPacked("p-2"))
                .expectCompletedStep("packed")
                .expectOutcome(HandlingOutcome.WAITING)
                .expectStatus(SagaStatus.RUNNING)
                .expectCommands(new LabelParcel("p-2"));
        assertThat(failed.result().outboxFailures()).singleElement()
                .satisfies(failure -> assertThat(failure).hasMessage("mail down"));
    }

    @Test
    @DisplayName("G-009-04: record mode needs the outbox command's handler class registered, and does not run it")
    void outboxCommandInRecordMode() {
        SagaFixture.of(DispatchSaga.class).build()
                .when(new ParcelPacked("p-1"))
                .expectStatus(SagaStatus.FAILED)
                .expectFailure(io.github.gkosharovdev.sagaforge.core.command.NoCommandHandlerException.class,
                        io.github.gkosharovdev.sagaforge.core.failure.FailureClassification.UNCLASSIFIED);

        Then then = SagaFixture.of(DispatchSaga.class).register(Notifier.class).build()
                .when(new ParcelPacked("p-1"))
                .expectCommands(new LabelParcel("p-1"), new NotifyCustomer("p-1"))
                .expectStatus(SagaStatus.RUNNING);
        assertThat(then.result().commands()).extracting(RecordedCommand::handled).containsOnly(false);
    }

    @Test
    @DisplayName("failure rules apply to handled commands; givenAggregates needs handler mode")
    void rulesAndMisuse() {
        SagaFixture fixture = SagaFixture.of(ShopSaga.class).handlers(new PaymentDesk())
                .failCommand(TakePayment.class, CardDeclined::new).build();
        fixture.when(new OrderPlaced("o-1", "10")).expectStatus(SagaStatus.FAILED);

        assertThatThrownBy(() -> SagaFixture.of(ShopSaga.class).build().givenAggregates(ShopOrder.create("o", true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("handler mode");
        assertThatThrownBy(() -> SagaFixture.of(ShopSaga.class).handleAggregates().build()
                .givenAggregates(ShopOrder.create("o", true)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("register its class");
    }
}
