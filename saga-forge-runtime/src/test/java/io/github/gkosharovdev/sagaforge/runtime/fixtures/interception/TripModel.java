package io.github.gkosharovdev.sagaforge.runtime.fixtures.interception;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/**
 * The model of {@code CommandInterceptorTest} ({@code specs/009-saga-test-fixture} tasks.md T004): a trip saga that
 * books a hotel (handled), pings a tracker (no handler), books a flight with retries (handled), notifies the
 * traveller through the outbox (handled), and cancels the hotel as the compensation of its start step.
 */
public final class TripModel {

    public static final String START = "onTripRequested";
    public static final String HOTEL = "onHotelBooked";
    public static final String END = "onFlightBooked";
    public static final String UNAVAILABLE = "onFlightUnavailable";
    public static final String CANCEL = "cancelHotel";

    private TripModel() {
    }

    /** The aggregate the trip events belong to; it has no handlers. */
    @Aggregate
    public static final class Trip {

        @AggregateIdentifier
        private String tripId;
    }

    @DomainEvent(aggregate = Trip.class)
    public record TripRequested(@AggregateIdentifier String tripId) {
    }

    @DomainEvent(aggregate = Trip.class)
    public record HotelBooked(@AggregateIdentifier String tripId) {
    }

    @DomainEvent(aggregate = Trip.class)
    public record FlightBooked(@AggregateIdentifier String tripId) {
    }

    @DomainEvent(aggregate = Trip.class)
    public record FlightUnavailable(@AggregateIdentifier String tripId) {
    }

    @Command
    public record BookHotel(String tripId) {
    }

    @Command
    public record CancelHotel(String tripId) {
    }

    /** No handler exists for it unless {@link Tracker} is registered. */
    @Command
    public record PingTracker(String tripId) {
    }

    @Command
    public record BookFlight(String tripId) {
    }

    @Command(delivery = CommandDelivery.OUTBOX)
    public record NotifyTraveller(String tripId) {
    }

    @Saga
    public static final class TripSaga {

        @SagaId
        private String sagaId;

        private CommandGateway commandGateway;

        @StartSaga
        @SagaStep(associationProperty = "tripId")
        public void onTripRequested(TripRequested event) {
            commandGateway.send(new BookHotel(event.tripId()));
            commandGateway.send(new PingTracker(event.tripId()));
        }

        @SagaStep(associationProperty = "tripId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 10))
        public void onHotelBooked(HotelBooked event) {
            commandGateway.send(new BookFlight(event.tripId()));
        }

        @EndSaga
        @SagaStep(associationProperty = "tripId")
        public void onFlightBooked(FlightBooked event) {
            commandGateway.send(new NotifyTraveller(event.tripId()));
        }

        @StartCompensation
        @SagaStep(associationProperty = "tripId")
        public void onFlightUnavailable(FlightUnavailable event) {
            // The trigger is the outcome.
        }

        @SagaCompensation(forStep = START)
        public void cancelHotel() {
            commandGateway.send(new CancelHotel(sagaId));
        }
    }

    /** Handles every command but {@link PingTracker}; records the identity each handler saw. */
    public static final class TravelDesk {

        private final List<Object> handled = new CopyOnWriteArrayList<>();
        private final List<CommandId> identities = new CopyOnWriteArrayList<>();

        @CommandHandler
        public void handle(BookHotel command, CommandId id) {
            seen(command, id);
        }

        @CommandHandler
        public void handle(CancelHotel command, CommandId id) {
            seen(command, id);
        }

        @CommandHandler
        public void handle(BookFlight command, CommandId id) {
            seen(command, id);
        }

        @CommandHandler
        public void handle(NotifyTraveller command, CommandId id) {
            seen(command, id);
        }

        private void seen(Object command, CommandId id) {
            handled.add(command);
            identities.add(id);
        }

        public List<Object> handled() {
            return List.copyOf(handled);
        }

        public List<CommandId> identities() {
            return List.copyOf(identities);
        }
    }

    /** Handles {@link PingTracker}; registered only where a test wants every command handled. */
    public static final class Tracker {

        private final List<CommandId> identities = new CopyOnWriteArrayList<>();

        @CommandHandler
        public void handle(PingTracker command, CommandId id) {
            identities.add(id);
        }

        public List<CommandId> identities() {
            return List.copyOf(identities);
        }
    }
}
