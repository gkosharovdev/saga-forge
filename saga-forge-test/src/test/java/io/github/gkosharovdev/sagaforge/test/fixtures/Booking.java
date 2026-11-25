package io.github.gkosharovdev.sagaforge.test.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/**
 * A seat hold with a deadline ({@code specs/017-saga-deadlines}): {@code requested} (start) holds
 * the seat and starts the {@code hold} deadline for the event's {@code holdFor}; {@code paid} ends
 * the saga in time; {@code holdExpired} starts compensation when the deadline comes due, and {@code
 * releaseSeat} undoes {@code requested}. All on {@code associationProperty = "bookingId"}.
 */
public final class Booking {

  public static final String REQUESTED = "requested";
  public static final String PAID = "paid";
  public static final String EXPIRED = "holdExpired";
  public static final String HOLD = "hold";

  private Booking() {}

  @Aggregate
  public static final class Seat {

    @AggregateIdentifier private String bookingId;
  }

  @DomainEvent(aggregate = Seat.class)
  public record BookingRequested(@AggregateIdentifier String bookingId, String holdFor) {}

  @DomainEvent(aggregate = Seat.class)
  public record BookingPaid(@AggregateIdentifier String bookingId) {}

  @Command
  public record HoldSeat(String bookingId) {}

  @Command
  public record ReleaseSeat(String bookingId) {}

  @Saga
  public static final class BookingSaga {

    @SagaId private String sagaId;

    private SagaContext context;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "bookingId")
    @StartDeadline(name = HOLD, durationFrom = "holdFor", cancelledBy = PAID)
    public void requested(BookingRequested event) {
      context.putState("bookingId", event.bookingId());
      context.putState("holdFor", event.holdFor());
      commandGateway.send(new HoldSeat(event.bookingId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "bookingId")
    public void paid(BookingPaid event) {
      // paid in time
    }

    @StartCompensation
    @DeadlineExpired(HOLD)
    @SagaStep
    public void holdExpired(DeadlineReached deadline) {
      // the hold lapsed; compensation releases the seat
    }

    @SagaCompensation(forStep = REQUESTED)
    public void releaseSeat() {
      commandGateway.send(
          new ReleaseSeat(context.getState("bookingId", String.class).orElseThrow()));
    }
  }
}
