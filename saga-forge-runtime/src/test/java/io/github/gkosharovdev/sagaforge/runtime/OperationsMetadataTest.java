package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstanceQuery;
import io.github.gkosharovdev.sagaforge.core.operations.SagaOperations;
import io.github.gkosharovdev.sagaforge.core.spi.EventDelivery;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.ThreadLocalMetadataAccessor;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.NotifyCourier;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.Parcel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelPicked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.PrintLabel;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 2 ({@code specs/019-process-metadata} T034): the operations view returns the metadata
 * the runtime wrote. The instance keeps its start metadata ({@code c-1}); the entry of a step a
 * later delivery triggered carries that delivery's ({@code c-2}); an operator repair's entries
 * carry the instance's.
 */
class OperationsMetadataTest {

  private static final SagaMetadata C1 = SagaMetadata.of(Map.of("correlationId", "c-1"));
  private static final SagaMetadata C2 = SagaMetadata.of(Map.of("correlationId", "c-2"));

  private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
  private final TransactionalInMemoryStorageProvider storage =
      new TransactionalInMemoryStorageProvider(boundary);
  private final ThreadLocalMetadataAccessor accessor = new ThreadLocalMetadataAccessor();
  private SagaRuntime engine;
  private String sagaId;

  @BeforeEach
  void setUp() {
    MetadataModel.reset(accessor);
    engine =
        SagaRuntime.builder()
            .register(ParcelSaga.class, ParcelService.class, Parcel.class)
            .register(PrintLabel.class, NotifyCourier.class)
            .storage(storage)
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .outbox(CapturingOutbox.inTransaction(boundary).supportingCommands())
            .handlerInstances(new MapHandlerInstanceProvider().register(new ParcelService()))
            .metadataAccessor(accessor)
            .build();
    engine.start();
    sagaId =
        engine
            .receive(new EventDelivery(new ParcelBooked("p-1", 0), null, C1))
            .results()
            .get(0)
            .sagaId();
    engine.receive(new EventDelivery(new ParcelPicked("p-1"), null, C2));
  }

  @Test
  @DisplayName("find, findByAssociation and list return the instance's start metadata")
  void instanceViews() {
    SagaOperations operations = engine.operations();

    assertThat(operations.find(sagaId)).map(SagaInstance::metadata).contains(C1);
    assertThat(operations.findByAssociation("parcelId", "p-1"))
        .extracting(SagaInstance::metadata)
        .containsExactly(C1);
    assertThat(operations.list(SagaInstanceQuery.all()).instances())
        .extracting(SagaInstance::metadata)
        .containsExactly(C1);
  }

  @Test
  @DisplayName("history: the start's entries carry c-1, the later step's entry c-2")
  void historyViews() {
    List<SagaLifecycleEntry> history = engine.operations().history(sagaId);

    assertThat(history)
        .filteredOn(entry -> "onBooked".equals(entry.stepName()))
        .extracting(SagaLifecycleEntry::metadata)
        .isNotEmpty()
        .containsOnly(C1);
    assertThat(history)
        .filteredOn(entry -> "onPicked".equals(entry.stepName()))
        .extracting(SagaLifecycleEntry::metadata)
        .isNotEmpty()
        .containsOnly(C2);
  }

  @Test
  @DisplayName("an operator repair's entries carry the instance's metadata")
  void operatorEntries() {
    int before = engine.operations().history(sagaId).size();

    engine.operations().compensate(sagaId, new OperatorRequest("ops", "customer cancelled"));

    List<SagaLifecycleEntry> history = engine.operations().history(sagaId);
    assertThat(history.subList(before, history.size()))
        .isNotEmpty()
        .anySatisfy(
            entry ->
                assertThat(entry.kind()).isEqualTo(SagaLifecycleEventKind.COMPENSATION_STARTED))
        .extracting(SagaLifecycleEntry::metadata)
        .containsOnly(C1);
  }
}
