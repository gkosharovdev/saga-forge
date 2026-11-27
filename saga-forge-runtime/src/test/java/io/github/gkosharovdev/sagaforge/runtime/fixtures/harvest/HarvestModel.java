package io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The model of the harvest tests ({@code specs/016-marker-spi-jmolecules} US2): a {@link Parcel}
 * keeps the events it raises in a pending list instead of calling {@code DomainEvents.record}, and
 * a {@link PendingEvents} source takes them.
 *
 * <p>{@link ParcelSaga}: {@code @StartSaga onRequested} creates the parcel ({@link
 * ParcelRegistered} pending); {@code onRegistered} dispatches it ({@link ParcelDispatched}, {@link
 * ParcelTracked} pending) and labels it ({@link ParcelLabelled} recorded, {@link ParcelWeighed}
 * pending); {@code onDispatched} notifies the customer through a service; {@code @EndSaga
 * onWeighed}. {@link NotingSaga} and {@link NotesAuditSaga} serve Property 49.
 */
public final class HarvestModel {

  private HarvestModel() {}

  /** An aggregate that holds the events it raises until a source takes them. */
  @Aggregate
  public static final class Parcel {

    @AggregateIdentifier private final String parcelId;

    private final List<Object> pending = new ArrayList<>();

    private Parcel(String parcelId) {
      this.parcelId = parcelId;
    }

    @CommandHandler
    public static Parcel register(RegisterParcel command) {
      Parcel parcel = new Parcel(command.parcelId());
      parcel.pending.add(new ParcelRegistered(command.parcelId()));
      return parcel;
    }

    @CommandHandler
    public void handle(DispatchParcel command) {
      pending.add(new ParcelDispatched(parcelId));
      pending.add(new ParcelTracked(parcelId));
    }

    /** Records one event itself and holds another. */
    @CommandHandler
    public void handle(LabelParcel command) {
      DomainEvents.record(new ParcelLabelled(parcelId));
      pending.add(new ParcelWeighed(parcelId));
    }

    /** Raises one {@link ParcelNoted} per note, recorded or held as the command says. */
    @CommandHandler
    public void handle(NoteParcel command) {
      for (int note : command.notes()) {
        ParcelNoted noted = new ParcelNoted(parcelId, note);
        if (command.recordExplicitly()) {
          DomainEvents.record(noted);
        } else {
          pending.add(noted);
        }
      }
      ParcelClosed closed = new ParcelClosed(parcelId);
      if (command.recordExplicitly()) {
        DomainEvents.record(closed);
      } else {
        pending.add(closed);
      }
    }

    public String parcelId() {
      return parcelId;
    }

    /** The pending events, cleared. */
    public List<Object> takePending() {
      List<Object> taken = List.copyOf(pending);
      pending.clear();
      return taken;
    }
  }

  /** Non-transactional parcel store. */
  public static final class ParcelRepository {

    private final Map<String, Parcel> parcels = new ConcurrentHashMap<>();

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

  /** A service handler; it is never harvested. */
  public static final class Notifier {

    private final List<String> notified = new ArrayList<>();
    private boolean failNext;

    @CommandHandler
    public void handle(NotifyCustomer command) {
      if (failNext) {
        failNext = false;
        throw new NotifierUnavailable();
      }
      notified.add(command.parcelId());
    }

    /** The next notification throws a transient failure. */
    public void failNext() {
      failNext = true;
    }

    public List<String> notified() {
      return List.copyOf(notified);
    }
  }

  /** A transient failure of {@link Notifier}. */
  public static final class NotifierUnavailable extends RuntimeException
      implements TransientFailure {

    NotifierUnavailable() {
      super("notifier unavailable");
    }
  }

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelRequested(@AggregateIdentifier String parcelId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelRegistered(@AggregateIdentifier String parcelId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelDispatched(@AggregateIdentifier String parcelId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelTracked(@AggregateIdentifier String parcelId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelLabelled(@AggregateIdentifier String parcelId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelWeighed(@AggregateIdentifier String parcelId) {}

  @DomainEvent(aggregate = Parcel.class)
  public record NotingRequested(
      @AggregateIdentifier String parcelId, List<Integer> notes, boolean recordExplicitly) {}

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelNoted(@AggregateIdentifier String parcelId, int note) {}

  @DomainEvent(aggregate = Parcel.class)
  public record ParcelClosed(@AggregateIdentifier String parcelId) {}

  @Command
  public record RegisterParcel(String parcelId) {}

  @Command
  public record DispatchParcel(@AggregateIdentifier String parcelId) {}

  @Command
  public record LabelParcel(@AggregateIdentifier String parcelId) {}

  @Command
  public record NoteParcel(
      @AggregateIdentifier String parcelId, List<Integer> notes, boolean recordExplicitly) {}

  @Command
  public record NotifyCustomer(String parcelId) {}

  @Saga
  public static final class ParcelSaga {

    @SagaId private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "parcelId")
    public void onRequested(ParcelRequested event) {
      commandGateway.send(new RegisterParcel(event.parcelId()));
    }

    @SagaStep(associationProperty = "parcelId")
    public void onRegistered(ParcelRegistered event) {
      commandGateway.send(new DispatchParcel(event.parcelId()));
      commandGateway.send(new LabelParcel(event.parcelId()));
    }

    @SagaStep(
        associationProperty = "parcelId",
        retry = @RetryPolicy(maxAttempts = 2, initialBackoffMillis = 0))
    public void onDispatched(ParcelDispatched event) {
      commandGateway.send(new NotifyCustomer(event.parcelId()));
    }

    @EndSaga
    @SagaStep(associationProperty = "parcelId")
    public void onWeighed(ParcelWeighed event) {
      // Outcome step.
    }
  }

  /** Creates a parcel and notes it; ends when the parcel is closed (Property 49). */
  @Saga
  public static final class NotingSaga {

    @SagaId private String sagaId;

    private CommandGateway commandGateway;

    @StartSaga
    @SagaStep(associationProperty = "parcelId")
    public void onRequested(NotingRequested event) {
      commandGateway.send(new RegisterParcel(event.parcelId()));
      commandGateway.send(
          new NoteParcel(event.parcelId(), event.notes(), event.recordExplicitly()));
    }

    @SagaStep(associationProperty = "parcelId")
    public void onNoted(ParcelNoted event) {
      // Continues in process.
    }

    @EndSaga
    @SagaStep(associationProperty = "parcelId")
    public void onClosed(ParcelClosed event) {
      // Outcome step.
    }
  }

  /** A second saga type with steps for the noting events: fan-out (Property 47). */
  @Saga
  public static final class NotesAuditSaga {

    @SagaId private String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "parcelId")
    public void onNoted(ParcelNoted event) {
      // Opens the audit.
    }

    @EndSaga
    @SagaStep(associationProperty = "parcelId")
    public void onClosed(ParcelClosed event) {
      // Outcome step.
    }
  }
}
