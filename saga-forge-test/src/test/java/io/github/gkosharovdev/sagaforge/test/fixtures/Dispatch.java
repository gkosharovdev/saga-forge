package io.github.gkosharovdev.sagaforge.test.fixtures;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;

/**
 * Handler-mode model (tasks.md T041): {@code DispatchSaga} labels a parcel, an aggregate loaded through a client
 * {@code @AggregateLoader}/{@code @AggregateSaver} ({@link ParcelStore}), and notifies the customer through the outbox;
 * the notification's event ends it.
 */
public final class Dispatch {

    private Dispatch() {
    }

    @Aggregate
    public static final class Parcel {

        @AggregateIdentifier
        private final String parcelId;
        private boolean labelled;

        public Parcel(String parcelId) {
            this.parcelId = parcelId;
        }

        @CommandHandler
        public void handle(LabelParcel command) {
            labelled = true;
        }

        public String parcelId() {
            return parcelId;
        }

        public boolean labelled() {
            return labelled;
        }
    }

    /** The client's own store for parcels: it wins over the fixture's aggregate store. */
    public static final class ParcelStore {

        private final Map<String, Parcel> parcels = new LinkedHashMap<>();

        public ParcelStore with(Parcel parcel) {
            parcels.put(parcel.parcelId(), parcel);
            return this;
        }

        @AggregateLoader
        public Parcel load(String parcelId) {
            return parcels.get(parcelId);
        }

        @AggregateSaver
        public void save(Parcel parcel) {
            parcels.put(parcel.parcelId(), parcel);
        }

        public Parcel parcel(String parcelId) {
            return parcels.get(parcelId);
        }
    }

    @DomainEvent(aggregate = Parcel.class)
    public record ParcelPacked(@AggregateIdentifier String parcelId) {
    }

    @DomainEvent(aggregate = Parcel.class)
    public record CustomerNotified(@AggregateIdentifier String parcelId) {
    }

    @Command
    public record LabelParcel(@AggregateIdentifier String parcelId) {
    }

    @Command(delivery = CommandDelivery.OUTBOX)
    public record NotifyCustomer(String parcelId) {
    }

    /** Sends notifications; records the event that ends the saga. */
    public static final class Notifier {

        private final List<NotifyCustomer> sent = new CopyOnWriteArrayList<>();

        @CommandHandler
        public void handle(NotifyCustomer command) {
            sent.add(command);
            DomainEvents.record(new CustomerNotified(command.parcelId()));
        }

        public List<NotifyCustomer> sent() {
            return List.copyOf(sent);
        }
    }

    @Saga
    public static final class DispatchSaga {

        @SagaId
        private String sagaId;

        private CommandGateway commandGateway;

        @StartSaga
        @SagaStep(associationProperty = "parcelId")
        public void packed(ParcelPacked event) {
            commandGateway.send(new LabelParcel(event.parcelId()));
            commandGateway.send(new NotifyCustomer(event.parcelId()));
        }

        @EndSaga
        @SagaStep(associationProperty = "parcelId")
        public void notified(CustomerNotified event) {
            // Terminal step.
        }
    }
}
