package io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata;

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
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.ThreadLocalMetadataAccessor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The process-metadata fixture ({@code specs/019-process-metadata}): {@link ParcelSaga} reads its
 * {@code SagaMetadata} field and the thread's accessor state in every step and compensation; {@link
 * ParcelService} reads its {@code SagaMetadata} parameter in an in-transaction and an outbox
 * handler; {@link LabelSaga} starts from an event {@link ParcelService} records. Everything seen
 * goes to {@link #SEEN}.
 */
public final class MetadataModel {

  /** What one step, compensation or handler saw: its injected metadata and the thread's. */
  public record Seen(String where, SagaMetadata injected, Map<String, String> thread) {}

  /** Everything seen, in order. */
  public static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

  /** The accessor the fixture reads the thread's state from; set by the test. */
  public static volatile ThreadLocalMetadataAccessor accessor = new ThreadLocalMetadataAccessor();

  private static final Map<String, AtomicInteger> ATTEMPTS = new ConcurrentHashMap<>();

  private MetadataModel() {}

  public static void reset(ThreadLocalMetadataAccessor threadAccessor) {
    SEEN.clear();
    ATTEMPTS.clear();
    accessor = threadAccessor;
  }

  static void see(String where, SagaMetadata injected) {
    SEEN.add(new Seen(where, injected, accessor.capture()));
  }

  @Aggregate
  public static final class Parcel {
    @AggregateIdentifier private String parcelId;
  }

  /** Starts the saga; the start step fails transiently {@code transientFailures} times first. */
  @DomainEvent(aggregate = Parcel.class)
  public record ParcelBooked(@AggregateIdentifier String parcelId, int transientFailures) {}

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelPicked(@AggregateIdentifier String parcelId) {}

  /** The business failure: compensates the start step. */
  @DomainEvent(aggregate = Parcel.class)
  public record ParcelLost(@AggregateIdentifier String parcelId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelDelivered(@AggregateIdentifier String parcelId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record LabelPrinted(@AggregateIdentifier String parcelId) {}

  /** Handled in the sender's unit of work. */
  @Command
  public record PrintLabel(String parcelId) {}

  /** Stored in the outbox, handled after the sender commits. */
  @Command(delivery = CommandDelivery.OUTBOX)
  public record NotifyCourier(String parcelId) {}

  public static final class TransientParcelFailure extends RuntimeException
      implements TransientFailure {
    private static final long serialVersionUID = 1L;

    TransientParcelFailure() {
      super("try again");
    }
  }

  @Saga
  public static final class ParcelSaga {

    @SagaId private String sagaId;
    private CommandGateway commandGateway;
    private SagaMetadata metadata;

    @StartSaga
    @SagaStep(
        associationProperty = "parcelId",
        retry = @RetryPolicy(maxAttempts = 5, initialBackoffMillis = 0))
    public void onBooked(ParcelBooked event) {
      see("onBooked", metadata);
      commandGateway.send(new PrintLabel(event.parcelId()));
      commandGateway.send(new NotifyCourier(event.parcelId()));
      int attempt =
          ATTEMPTS.computeIfAbsent(event.parcelId(), id -> new AtomicInteger()).incrementAndGet();
      if (attempt <= event.transientFailures()) {
        throw new TransientParcelFailure();
      }
    }

    @SagaStep(associationProperty = "parcelId")
    public void onPicked(ParcelPicked event) {
      see("onPicked", metadata);
    }

    @StartCompensation
    @SagaStep(associationProperty = "parcelId")
    public void onLost(ParcelLost event) {
      see("onLost", metadata);
    }

    @SagaCompensation(forStep = "onBooked")
    public void undoBooked() {
      see("undoBooked", metadata);
    }

    @EndSaga
    @SagaStep(associationProperty = "parcelId")
    public void onDelivered(ParcelDelivered event) {
      see("onDelivered", metadata);
    }
  }

  /** Ends {@link LabelSaga}. */
  @DomainEvent(aggregate = Parcel.class)
  public record LabelFiled(@AggregateIdentifier String parcelId) {}

  /**
   * A second saga type, started by the {@link LabelPrinted} event {@link ParcelService} records:
   * the fan-out of one saga's event to another (014).
   */
  @Saga
  public static final class LabelSaga {

    @SagaId private String sagaId;
    private SagaMetadata metadata;

    @StartSaga
    @SagaStep(associationProperty = "parcelId")
    public void onLabelPrinted(LabelPrinted event) {
      see("onLabelPrinted", metadata);
    }

    @EndSaga
    @SagaStep(associationProperty = "parcelId")
    public void onLabelFiled(LabelFiled event) {
      see("onLabelFiled", metadata);
    }
  }

  public static final class ParcelService {

    @CommandHandler
    public void print(PrintLabel command, SagaMetadata metadata) {
      see("print", metadata);
    }

    @CommandHandler
    public void notifyCourier(NotifyCourier command, SagaMetadata metadata) {
      see("notifyCourier", metadata);
      DomainEvents.record(new LabelPrinted(command.parcelId()));
    }
  }
}
