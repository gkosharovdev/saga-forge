package io.github.gkosharovdev.sagaforge.test.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
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
 * Fan-out model (specs/014-publish-once Property 47): {@link PackingSaga} sends {@link Pack}, whose
 * handler records {@link Packed}; {@code PackingSaga} ends on it in process, and {@link LabelSaga}
 * starts on it.
 */
public final class Packing {

  private Packing() {}

  @Aggregate
  public static final class Parcel {

    @AggregateIdentifier private String orderId;
  }

  @DomainEvent(aggregate = Parcel.class)
  public record PackingRequested(@AggregateIdentifier String orderId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record Packed(@AggregateIdentifier String orderId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record Labelled(@AggregateIdentifier String orderId) {}

  @Command
  public record Pack(String orderId) {}

  /** Handles {@link Pack} by recording {@link Packed}. */
  public static final class PackingDesk {

    @CommandHandler
    public void handle(Pack command) {
      DomainEvents.record(new Packed(command.orderId()));
    }
  }

  @Saga
  public static final class PackingSaga {

    @SagaId private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onRequested(PackingRequested event) {
      commandGateway.send(new Pack(event.orderId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onPacked(Packed event) {
      // Outcome step, run in process.
    }
  }

  @Saga
  public static final class LabelSaga {

    @SagaId private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void onPacked(Packed event) {
      // Waits for the label.
    }

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void onLabelled(Labelled event) {
      // Outcome step.
    }
  }
}
