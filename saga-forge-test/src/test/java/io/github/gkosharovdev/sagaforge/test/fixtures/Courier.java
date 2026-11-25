package io.github.gkosharovdev.sagaforge.test.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/**
 * Record-mode model for outbox command failures ({@code specs/012-outbox-command-failure-feedback} tasks.md T036):
 * {@code CourierSaga} books a slot, then sends the outbox command {@link CollectParcel}; the courier's collection ends
 * it. Each step's compensation sends a command, so the compensations show in the recorded commands.
 */
public final class Courier {

    public static final String BOOKED = "booked";
    public static final String SCHEDULED = "scheduled";
    private static final String DELIVERY_ID = "deliveryId";

    private Courier() {
    }

    @Aggregate
    public record Delivery(@AggregateIdentifier String deliveryId) {
    }

    @DomainEvent(aggregate = Delivery.class)
    public record DeliveryBooked(@AggregateIdentifier String deliveryId) {
    }

    @DomainEvent(aggregate = Delivery.class)
    public record PickupScheduled(@AggregateIdentifier String deliveryId) {
    }

    @DomainEvent(aggregate = Delivery.class)
    public record ParcelCollected(@AggregateIdentifier String deliveryId) {
    }

    @Command(delivery = CommandDelivery.OUTBOX)
    public record CollectParcel(String deliveryId) {
    }

    @Command
    public record ReleaseSlot(String deliveryId) {
    }

    @Command
    public record CancelPickup(String deliveryId) {
    }

    /** The courier will never collect this parcel: not retried. */
    public static final class CourierRefused extends RuntimeException implements FinalFailure {

        public CourierRefused() {
            super("courier refused the parcel");
        }
    }

    /** Handler host; in record mode its methods never run, the fixture records and answers. */
    public static final class CourierService {

        @CommandHandler
        public void handle(CollectParcel command) {
            // Recorded only.
        }

        @CommandHandler
        public void handle(ReleaseSlot command) {
            // Recorded only.
        }

        @CommandHandler
        public void handle(CancelPickup command) {
            // Recorded only.
        }
    }

    @Saga
    public static final class CourierSaga {

        @SagaId
        private String sagaId;

        private CommandGateway commandGateway;

        private SagaContext context;

        @StartSaga
        @SagaStep(associationProperty = "deliveryId")
        public void booked(DeliveryBooked event) {
            context.putState(DELIVERY_ID, event.deliveryId());
        }

        @SagaStep(associationProperty = "deliveryId")
        public void scheduled(PickupScheduled event) {
            commandGateway.send(new CollectParcel(event.deliveryId()));
        }

        @EndSaga
        @SagaStep(associationProperty = "deliveryId")
        public void collected(ParcelCollected event) {
            // Terminal step.
        }

        private String deliveryId() {
            return context.getState(DELIVERY_ID, String.class).orElseThrow();
        }

        @SagaCompensation(forStep = BOOKED)
        public void releaseSlot(CompensationTrigger trigger) {
            commandGateway.send(new ReleaseSlot(deliveryId()));
        }

        @SagaCompensation(forStep = SCHEDULED)
        public void cancelPickup(CompensationTrigger trigger) {
            commandGateway.send(new CancelPickup(deliveryId()));
        }
    }
}
