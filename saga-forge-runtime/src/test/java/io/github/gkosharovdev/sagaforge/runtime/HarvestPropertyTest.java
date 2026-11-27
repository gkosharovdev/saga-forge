package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.NotesAuditSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.NotingRequested;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.NotingSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.Parcel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelRepository;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.PendingEvents;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Property 49 ({@code specs/016-marker-spi-jmolecules} FR-022): events an aggregate holds and a
 * source takes behave exactly as the same events recorded with {@code DomainEvents.record} as the
 * handler's last statements: equal recorded events, in-process continuations, outbox contents and
 * fan-out to a second saga type (Property 47).
 */
class HarvestPropertyTest {

  private static final String PARCEL = "p-1";

  /** What a run leaves behind, without ids or timestamps. */
  record Observation(
      List<Object> recorded,
      List<Object> handedOn,
      Map<String, List<String>> instances,
      List<String> fanOutOutcomes) {}

  @Property(tries = 100)
  void harvestedEqualsRecorded(@ForAll @Size(max = 5) List<@IntRange(max = 9) Integer> notes) {
    Observation harvested = run(notes, false);

    assertThat(harvested).isEqualTo(run(notes, true));
    assertThat(harvested.instances().get(NotingSaga.class.getName()))
        .singleElement()
        .asString()
        .startsWith("COMPLETED onClosed");
    assertThat(harvested.instances().containsKey(NotesAuditSaga.class.getName()))
        .isEqualTo(!notes.isEmpty());
  }

  private static Observation run(List<Integer> notes, boolean recordExplicitly) {
    InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    TransactionalInMemoryStorageProvider storage =
        new TransactionalInMemoryStorageProvider(boundary);
    CapturingOutbox outbox = CapturingOutbox.inTransaction(boundary);
    SagaRuntime runtime =
        SagaRuntime.builder()
            .register(NotingSaga.class, NotesAuditSaga.class, Parcel.class, ParcelRepository.class)
            .storage(storage)
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .outbox(outbox)
            .handlerInstances(new MapHandlerInstanceProvider().register(new ParcelRepository()))
            .aggregateEventSource(PendingEvents.forParcels())
            .build();
    runtime.start();

    runtime.receive(new NotingRequested(PARCEL, notes, recordExplicitly));
    List<OutgoingMessages> first = CapturingOutbox.withoutOutcomes(outbox.committed());
    List<Object> recorded =
        first.stream().flatMap(batch -> batch.recordedEvents().stream()).toList();
    List<Object> handedOn = first.stream().flatMap(batch -> batch.events().stream()).toList();
    List<String> fanOut = new ArrayList<>();
    for (OutgoingMessages batch : first) {
      for (Object event : batch.events()) {
        HandlingReport report = runtime.receive(event, batch.sagaId());
        report.results().forEach(result -> fanOut.add(result.sagaType() + ":" + result.outcome()));
      }
    }
    return new Observation(recorded, handedOn, instances(storage), fanOut);
  }

  private static Map<String, List<String>> instances(TransactionalInMemoryStorageProvider storage) {
    Map<String, List<String>> bySagaType = new TreeMap<>();
    for (SagaInstance instance : storage.committed().all()) {
      bySagaType
          .computeIfAbsent(instance.sagaType(), type -> new ArrayList<>())
          .add(instance.status() + " " + instance.outcome() + " " + stepStatuses(instance));
    }
    bySagaType.values().forEach(list -> list.sort(String::compareTo));
    return bySagaType;
  }

  private static Map<String, String> stepStatuses(SagaInstance instance) {
    Map<String, String> statuses = new TreeMap<>();
    instance
        .steps()
        .forEach((step, record) -> statuses.put(step, record.status() + "x" + record.attempts()));
    return statuses;
  }
}
