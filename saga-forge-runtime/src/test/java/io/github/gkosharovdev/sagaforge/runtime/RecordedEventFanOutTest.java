package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.BillingSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.PackingSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.PackingService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.Shipment;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.ShipmentPacked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.publishonce.ShipmentRequested;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Property 47, fan-out of recorded events (specs/014-publish-once
 * contracts/event-receiver-source.md FO1 to FO3, SR2; closes G-ENG-24): {@link PackingService}
 * records {@link ShipmentPacked}, which {@link PackingSaga} consumes in process and {@link
 * BillingSaga} starts on.
 */
class RecordedEventFanOutTest {

  private static final String ORDER = "o-1";

  /** One runtime over in-memory doubles and an in-transaction capturing outbox. */
  private static final class Run {

    final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    final TransactionalInMemoryStorageProvider storage =
        new TransactionalInMemoryStorageProvider(boundary);
    final CapturingOutbox outbox = CapturingOutbox.inTransaction(boundary);
    final PackingService packing = new PackingService();
    final SagaRuntime runtime;

    Run(boolean withBilling) {
      List<Class<?>> model = new ArrayList<>(List.of(PackingSaga.class, PackingService.class));
      model.add(Shipment.class);
      if (withBilling) {
        model.add(BillingSaga.class);
      }
      runtime =
          SagaRuntime.builder()
              .register(model.toArray(Class<?>[]::new))
              .storage(storage)
              .contextStore(new InMemorySagaContextStore())
              .transactions(boundary)
              .dispatcher(new RecordingEventDispatcher())
              .outbox(outbox)
              .handlerInstances(new MapHandlerInstanceProvider().register(packing))
              .build();
      runtime.start();
    }

    /** Delivers every committed event as a transport would: with the batch's saga id. */
    List<HandlingReport> deliverCommitted() {
      List<HandlingReport> reports = new ArrayList<>();
      for (OutgoingMessages batch : CapturingOutbox.withoutOutcomes(outbox.committed())) {
        for (Object event : batch.events()) {
          reports.add(runtime.receive(event, batch.sagaId()));
        }
      }
      return reports;
    }

    List<SagaInstance> instancesOf(Class<?> sagaType) {
      return storage.committed().all().stream()
          .filter(instance -> instance.sagaType().equals(sagaType.getName()))
          .toList();
    }

    long packedEventsHandedOn() {
      return CapturingOutbox.withoutOutcomes(outbox.committed()).stream()
          .flatMap(batch -> batch.events().stream())
          .filter(ShipmentPacked.class::isInstance)
          .count();
    }
  }

  @Test
  @DisplayName("FO1: an event its own instance ran in process also starts the other saga type")
  void fansOutToOtherSagaType() {
    Run run = new Run(true);

    SagaHandlingResult packing = run.runtime.receive(new ShipmentRequested(ORDER)).results().get(0);
    List<HandlingReport> reports = run.deliverCommitted();

    assertThat(packing.outcome()).isEqualTo(HandlingOutcome.WAITING);
    assertThat(run.packedEventsHandedOn()).isEqualTo(1);
    assertThat(reports)
        .singleElement()
        .satisfies(
            report ->
                assertThat(report.results())
                    .singleElement()
                    .satisfies(
                        billing -> {
                          assertThat(billing.sagaType()).isEqualTo(BillingSaga.class.getName());
                          assertThat(billing.started()).isTrue();
                          assertThat(billing.outcome()).isEqualTo(HandlingOutcome.WAITING);
                        }));
    assertThat(run.instancesOf(BillingSaga.class)).hasSize(1);
    SagaInstance source = run.instancesOf(PackingSaga.class).get(0);
    assertThat(source.steps().get(PackingSaga.PACKED_STEP).status())
        .isEqualTo(StepStatus.COMPLETED);
    assertThat(source.steps().get(PackingSaga.PACKED_STEP).attempts()).isEqualTo(1);
  }

  @Test
  @DisplayName("FO3: a rolled-back unit of work hands nothing on")
  void rollbackHandsNothingOn() {
    Run run = new Run(true);
    run.packing.failNext();

    run.runtime.receive(new ShipmentRequested(ORDER));
    run.deliverCommitted();

    assertThat(run.packedEventsHandedOn()).isZero();
    assertThat(run.instancesOf(BillingSaga.class)).isEmpty();
  }

  @Test
  @DisplayName("no other saga type has a step: the event is not handed on (unchanged)")
  void noOtherSagaTypeNoMessage() {
    Run run = new Run(false);

    run.runtime.receive(new ShipmentRequested(ORDER));

    assertThat(run.packedEventsHandedOn()).isZero();
  }

  /**
   * Property 47: for any number of deliveries of the stored message, the other saga type has one
   * instance, the source instance's step ran once, and no delivery reports a result for the source.
   */
  @Property(tries = 60)
  void property47(
      @ForAll boolean withBilling,
      @ForAll boolean recordingFails,
      @ForAll @IntRange(min = 1, max = 3) int deliveries) {
    Run run = new Run(withBilling);
    if (recordingFails) {
      run.packing.failNext();
    }

    run.runtime.receive(new ShipmentRequested(ORDER));
    List<HandlingReport> reports = new ArrayList<>();
    for (int round = 0; round < deliveries; round++) {
      reports.addAll(run.deliverCommitted());
    }

    boolean committed = !recordingFails;
    assertThat(run.instancesOf(BillingSaga.class)).hasSize(withBilling && committed ? 1 : 0);
    assertThat(run.packedEventsHandedOn()).isEqualTo(withBilling && committed ? 1 : 0);
    assertThat(reports)
        .flatMap(HandlingReport::results)
        .noneMatch(result -> result.sagaType().equals(PackingSaga.class.getName()));
    if (committed) {
      SagaInstance source = run.instancesOf(PackingSaga.class).get(0);
      assertThat(source.steps().get(PackingSaga.PACKED_STEP).attempts()).isEqualTo(1);
    }
  }
}
