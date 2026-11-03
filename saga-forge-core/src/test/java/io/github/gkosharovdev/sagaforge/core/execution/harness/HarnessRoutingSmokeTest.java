package io.github.gkosharovdev.sagaforge.core.execution.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.AggregateNotFoundException;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.NoCommandHandlerException;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.AvailabilityClaimedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingConfirmedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.ClaimAvailability;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.ConfirmBooking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Kind;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult.Outcome;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateReference;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Smoke test of the harness' aggregate routing ({@link HarnessCommandGateway}, {@link HarnessAggregateAccess},
 * {@link InMemoryAggregateResolver}) and {@link HarnessEventPublisher}, driven through a minimal test-local saga
 * whose start step sends whatever command its event carries.
 */
class HarnessRoutingSmokeTest {

    private static final LocalDate CHECK_IN = LocalDate.of(2026, 3, 1);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 3, 4);
    private static final String INVENTORY = "inv-1";

    /** Start event of {@link RoutingSaga}: carries the command its start step sends. */
    @DomainEvent(aggregate = Booking.class)
    public record RouteRequested(@AggregateIdentifier String bookingId, Object command) {
    }

    /** End event of {@link RoutingSaga}. */
    @DomainEvent(aggregate = Booking.class)
    public record RouteFinished(@AggregateIdentifier String bookingId) {
    }

    /** Minimal saga: the start step sends the carried command, then waits for {@link RouteFinished}. */
    @Saga
    public static class RoutingSaga {
        @SagaId
        private String sagaId;
        private CommandGateway commandGateway;

        @StartSaga
        @SagaStep(associationProperty = "bookingId")
        public void onRouteRequested(RouteRequested event) {
            commandGateway.send(event.command());
        }

        @EndSaga
        @SagaStep(associationProperty = "bookingId")
        public void onRouteFinished(RouteFinished event) {
        }
    }

    /** Command handled by {@link PublishingService}. */
    @Command
    public record PublishFinished(String bookingId) {
    }

    /** Service that publishes {@link RouteFinished} through an {@link EventPublisher} set after the harness is built. */
    public static class PublishingService {
        private EventPublisher publisher;

        @CommandHandler
        public void handle(PublishFinished command) {
            publisher.publish(new RouteFinished(command.bookingId()));
        }
    }

    private InventoryRepository inventories;
    private BookingRepository bookings;

    @BeforeEach
    void setUp() {
        Booking.resetFaults();
        inventories = new InventoryRepository();
        bookings = new BookingRepository();
    }

    private static RouteRequested claim(String bookingId, String inventoryId) {
        return new RouteRequested(bookingId, new ClaimAvailability(inventoryId, bookingId, CHECK_IN, CHECK_OUT));
    }

    @Test
    void instanceHandlerIsLoadedAndSavedOnceThroughTheClientRepository() {
        inventories.add(new Inventory(INVENTORY));
        SagaTestHarness harness = SagaTestHarness.builder()
                .saga(RoutingSaga.class).aggregate(Inventory.class).repository(inventories).build();

        UnitOfWorkResult result = harness.deliver(claim("b-1", INVENTORY));

        assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(inventories.saveCount()).isEqualTo(1);
        assertThat(inventories.load(INVENTORY).claim(Inventory.claimIdFor("b-1"))).isPresent();
        assertThat(result.dispatched()).containsExactly(
                new AvailabilityClaimedEvent(INVENTORY, "b-1", Inventory.claimIdFor("b-1")));
        assertThat(harness.instance(result.sagaId()).steps().get("onRouteRequested").touchedAggregates())
                .containsExactly(AggregateReference.of(Inventory.class, INVENTORY));
    }

    @Test
    void creationHandlerSavesTheNewAggregate() {
        SagaTestHarness harness = SagaTestHarness.builder()
                .saga(RoutingSaga.class).aggregate(Booking.class).repository(bookings).build();

        UnitOfWorkResult result = harness.deliver(new RouteRequested("b-2", new ConfirmBooking("b-2", "c-2", "p-2")));

        assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(bookings.saveCount()).isEqualTo(1);
        assertThat(bookings.load("b-2").claimId()).isEqualTo("c-2");
        assertThat(result.dispatched()).containsExactly(new BookingConfirmedEvent("b-2", "c-2", "p-2"));
    }

    @Test
    void creationHandlerWithoutSaverOrResolverSavesNothing() {
        SagaTestHarness harness = SagaTestHarness.builder().saga(RoutingSaga.class).aggregate(Booking.class).build();

        UnitOfWorkResult result = harness.deliver(new RouteRequested("b-3", new ConfirmBooking("b-3", "c", "p")));

        assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(harness.aggregates().all(Booking.class)).isEmpty();
    }

    @Test
    void missingAggregateFailsAndRollsBackTheUnitOfWork() {
        SagaTestHarness harness = SagaTestHarness.builder()
                .saga(RoutingSaga.class).aggregate(Inventory.class).repository(inventories).build();

        UnitOfWorkResult result = harness.deliver(claim("b-4", "inv-missing"));

        assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(result.cause()).isInstanceOfSatisfying(AggregateNotFoundException.class, e -> {
            assertThat(e.getAggregateType()).isEqualTo(Inventory.class);
            assertThat(e.getAggregateId()).isEqualTo("inv-missing");
        });
        assertThat(inventories.saveCount()).isZero();
        assertThat(harness.instance(result.sagaId()).status()).isEqualTo(SagaStatus.FAILED);
    }

    @Test
    void commandWithoutHandlerFailsAndRollsBack() {
        SagaTestHarness harness = SagaTestHarness.builder().saga(RoutingSaga.class).build();

        UnitOfWorkResult result = harness.deliver(new RouteRequested("b-5", new ConfirmBooking("b-5", "c", "p")));

        assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(result.cause()).isInstanceOfSatisfying(NoCommandHandlerException.class,
                e -> assertThat(e.getCommandType()).isEqualTo(ConfirmBooking.class));
    }

    @Test
    void resolverIsUsedWhenNoClientLoaderOrSaverExists() {
        SagaTestHarness harness = SagaTestHarness.builder()
                .saga(RoutingSaga.class).resolvedAggregate(Inventory.class).build();
        harness.aggregates().registerCopier(Inventory.class, Inventory::copy);
        harness.aggregates().put(Inventory.class, INVENTORY, new Inventory(INVENTORY));
        harness.aggregates().put(Inventory.class, "inv-2", new Inventory("inv-2"));
        harness.aggregates().get(Inventory.class, "inv-2").orElseThrow().failNext();

        assertThat(harness.resolver().supports(Inventory.class)).isTrue();
        assertThat(harness.deliver(claim("b-6", INVENTORY)).outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(harness.aggregates().get(Inventory.class, INVENTORY).orElseThrow().claims()).hasSize(1);

        UnitOfWorkResult failed = harness.deliver(claim("b-7", "inv-2"));
        assertThat(failed.outcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(harness.aggregates().get(Inventory.class, "inv-2").orElseThrow().claims()).isEmpty();

        UnitOfWorkResult missing = harness.deliver(claim("b-8", "inv-missing"));
        assertThat(missing.cause()).isInstanceOf(AggregateNotFoundException.class);
    }

    @Test
    void buildRejectsAnAggregateWithInstanceHandlersButNoWayToLoadIt() {
        SagaTestHarness.Builder builder = SagaTestHarness.builder().saga(RoutingSaga.class).aggregate(Inventory.class);

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Inventory.class.getName())
                .hasMessageContaining("@AggregateLoader");
    }

    @Test
    void publisherInsideAUnitOfWorkRecordsLikeDomainEvents() {
        PublishingService service = new PublishingService();
        SagaTestHarness harness = SagaTestHarness.builder().saga(RoutingSaga.class).handler(service).build();
        service.publisher = harness.eventPublisher();

        UnitOfWorkResult result = harness.deliver(new RouteRequested("b-9", new PublishFinished("b-9")));

        assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(result.stepsRun()).containsExactly("onRouteRequested", "onRouteFinished");
        assertThat(harness.instance(result.sagaId()).status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(harness.eventLog().events(Kind.IN_PROCESS)).containsExactly(new RouteFinished("b-9"));
        assertThat(harness.dispatcher().dispatched()).isEmpty();
        assertThat(harness.eventPublisher().published()).isEmpty();
    }

    @Test
    void publisherOutsideAUnitOfWorkDispatchesImmediatelyAndStartsTheSaga() {
        inventories.add(new Inventory(INVENTORY));
        SagaTestHarness harness = SagaTestHarness.builder()
                .saga(RoutingSaga.class).aggregate(Inventory.class).repository(inventories).build();
        RouteRequested start = claim("b-10", INVENTORY);

        harness.eventPublisher().publish(start);

        assertThat(harness.eventPublisher().lastResults()).singleElement()
                .satisfies(result -> assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED));
        assertThat(harness.instances(RoutingSaga.class)).singleElement()
                .satisfies(instance -> assertThat(instance.status()).isEqualTo(SagaStatus.RUNNING));
        assertThat(harness.dispatcher().dispatched()).containsExactly(
                start, new AvailabilityClaimedEvent(INVENTORY, "b-10", Inventory.claimIdFor("b-10")));
        assertThat(harness.eventPublisher().published()).containsExactly(start);
    }

    @Test
    void redeliveryOptInDeliversAfterCommitDispatchesButThePublishedEventOnlyOnce() {
        inventories.add(new Inventory(INVENTORY));
        SagaTestHarness harness = SagaTestHarness.builder().saga(RoutingSaga.class).aggregate(Inventory.class)
                .repository(inventories).redeliverDispatched().build();

        harness.eventPublisher().publish(claim("b-11", INVENTORY));

        assertThat(harness.eventLog().entries(Kind.DELIVERED)).hasSize(1);
        assertThat(harness.eventLog().events(Kind.IGNORED)).containsExactly(
                new AvailabilityClaimedEvent(INVENTORY, "b-11", Inventory.claimIdFor("b-11")));
    }

    @Test
    void publisherRejectsNullAndNonDomainEvents() {
        HarnessEventPublisher publisher = SagaTestHarness.builder().saga(RoutingSaga.class).build().eventPublisher();

        assertThatThrownBy(() -> publisher.publish(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publish("not an event")).isInstanceOf(IllegalArgumentException.class);
        assertThat(publisher.published()).isEmpty();
    }
}
