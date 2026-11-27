package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateEventSource;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.Notifier;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.Parcel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelDispatched;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelLabelled;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelRegistered;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelRepository;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelRequested;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelTracked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelWeighed;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.PendingEvents;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Events held on an aggregate reach sagas ({@code specs/016-marker-spi-jmolecules} US2, FR-020 to
 * FR-025, contracts/aggregate-event-source.md): {@link Parcel} never records the events it holds;
 * {@link PendingEvents} takes them after each aggregate handler, before the save.
 */
class HarvestedEventsTest {

  private static final String PARCEL = "p-1";

  /** One runtime over in-memory doubles, an in-transaction capturing outbox and event sources. */
  private static final class Run {

    final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    final TransactionalInMemoryStorageProvider storage =
        new TransactionalInMemoryStorageProvider(boundary);
    final CapturingOutbox outbox = CapturingOutbox.inTransaction(boundary);
    final ParcelRepository parcels = new ParcelRepository();
    final Notifier notifier = new Notifier();
    final SagaRuntime runtime;

    Run(AggregateEventSource... sources) {
      SagaRuntime.Builder builder =
          SagaRuntime.builder()
              .register(ParcelSaga.class, Parcel.class, ParcelRepository.class, Notifier.class)
              .storage(storage)
              .contextStore(new InMemorySagaContextStore())
              .transactions(boundary)
              .dispatcher(new RecordingEventDispatcher())
              .outbox(outbox)
              .handlerInstances(
                  new MapHandlerInstanceProvider().register(parcels).register(notifier));
      for (AggregateEventSource source : sources) {
        builder.aggregateEventSource(source);
      }
      runtime = builder.build();
      runtime.start();
    }

    SagaHandlingResult request() {
      return runtime.receive(new ParcelRequested(PARCEL)).results().get(0);
    }

    /** Every event the committed units of work recorded, in order. */
    List<Object> recorded() {
      return CapturingOutbox.withoutOutcomes(outbox.committed()).stream()
          .map(OutgoingMessages::recordedEvents)
          .flatMap(List::stream)
          .toList();
    }
  }

  @Test
  @DisplayName(
      "creation and instance handlers: held events recorded in order, after the handler's own,"
          + " and only the new ones on a second command")
  void recordsInOrder() {
    Run run = new Run(PendingEvents.forParcels());

    SagaHandlingResult result = run.request();

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
    assertThat(run.recorded())
        .containsExactly(
            new ParcelRegistered(PARCEL),
            new ParcelDispatched(PARCEL),
            new ParcelTracked(PARCEL),
            new ParcelLabelled(PARCEL),
            new ParcelWeighed(PARCEL));
    assertThat(run.parcels.parcel(PARCEL).takePending()).isEmpty();
  }

  @Test
  @DisplayName("the next steps run in process on harvested events")
  void continuesInProcess() {
    Run run = new Run(PendingEvents.forParcels());

    run.request();

    assertThat(run.notifier.notified()).containsExactly(PARCEL);
    assertThat(run.storage.committed().all())
        .singleElement()
        .satisfies(instance -> assertThat(instance.outcome()).isEqualTo("onWeighed"));
  }

  @Test
  @DisplayName("without a source the held events stay on the aggregate and no step runs on them")
  void withoutSource() {
    Run run = new Run();

    SagaHandlingResult result = run.request();

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
    assertThat(run.recorded()).isEmpty();
    assertThat(run.parcels.parcel(PARCEL).takePending())
        .containsExactly(new ParcelRegistered(PARCEL));
  }

  @Test
  @DisplayName("a service handler is not harvested; only aggregates are offered to sources")
  void serviceNotHarvested() {
    PendingEvents source = PendingEvents.forParcels();
    Run run = new Run(source);

    run.request();

    assertThat(source.asked()).isNotEmpty().allMatch(type -> type == Parcel.class);
  }

  @Test
  @DisplayName("a retried unit of work records no event of the rolled-back attempt")
  void rollbackThenRetry() {
    Run run = new Run(PendingEvents.forParcels());
    run.notifier.failNext();

    SagaHandlingResult result = run.request();

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
    assertThat(run.storage.committed().all())
        .singleElement()
        .satisfies(
            instance -> assertThat(instance.steps().get("onDispatched").attempts()).isEqualTo(2));
    assertThat(run.recorded())
        .containsExactly(
            new ParcelRegistered(PARCEL),
            new ParcelDispatched(PARCEL),
            new ParcelTracked(PARCEL),
            new ParcelLabelled(PARCEL),
            new ParcelWeighed(PARCEL));
  }

  @Test
  @DisplayName("a throwing source fails the unit of work")
  void sourceThrows() {
    PendingEvents source = PendingEvents.forParcels();
    source.failNext(new IllegalStateException("event store down"));
    Run run = new Run(source);

    SagaHandlingResult result = run.request();

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
    assertThat(result.failure().message()).isEqualTo("event store down");
    assertThat(run.recorded()).isEmpty();
  }

  @Test
  @DisplayName("a held object that is not a domain event fails with the accepted markers")
  void unmarkedEvent() {
    PendingEvents source = PendingEvents.forParcels();
    source.addNext("not an event");
    Run run = new Run(source);

    SagaHandlingResult result = run.request();

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
    assertThat(result.failure().message())
        .isEqualTo(
            "Event source "
                + PendingEvents.class.getName()
                + " returned java.lang.String for aggregate "
                + Parcel.class.getName()
                + ", which is not a domain event (accepted: @DomainEvent)");
  }

  @Test
  @DisplayName("with two supporting sources only the first registered is asked")
  void firstSupportingSourceWins() {
    PendingEvents first = PendingEvents.forParcels();
    PendingEvents second = PendingEvents.forEverything();
    Run run = new Run(first, second);

    SagaHandlingResult result = run.request();

    assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
    assertThat(second.asked()).isEmpty();
  }
}
