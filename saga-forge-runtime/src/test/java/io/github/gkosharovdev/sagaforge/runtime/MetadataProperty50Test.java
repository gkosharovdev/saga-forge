package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.EventDelivery;
import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.ThreadLocalMetadataAccessor;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.NotifyCourier;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.Parcel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelDelivered;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelPicked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.ParcelService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.PrintLabel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.metadata.MetadataModel.Seen;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 50 (b) to (d) ({@code specs/019-process-metadata}, {@code
 * .kiro/specs/saga-forge-framework/design.md}): for any start metadata and any later delivery
 * metadata, the saga's {@code SagaMetadata} field, every handler's {@code SagaMetadata} parameter
 * (in-transaction, outbox and standalone) and every {@code OutgoingMessages} batch of an instance
 * carry the instance's metadata, with {@link MetadataAccessor#NONE} and with a thread-local
 * accessor alike.
 */
class MetadataProperty50Test {

  private static final int RUNS = 25;

  static Stream<Object[]> cases() {
    Random random = new Random(50);
    return Stream.iterate(0, run -> run + 1)
        .limit(RUNS)
        .map(run -> new Object[] {run % 2 == 0, metadata(random), metadata(random)});
  }

  private static SagaMetadata metadata(Random random) {
    Map<String, String> entries = new HashMap<>();
    int size = random.nextInt(4);
    for (int index = 0; index < size; index++) {
      entries.put("k" + random.nextInt(10), Integer.toString(random.nextInt(1_000)));
    }
    return SagaMetadata.of(entries);
  }

  @ParameterizedTest
  @MethodSource("cases")
  @DisplayName("Property 50: saga code and every outgoing batch see the instance's metadata")
  void instanceMetadataEverywhere(boolean threadLocal, SagaMetadata start, SagaMetadata later) {
    ThreadLocalMetadataAccessor thread = new ThreadLocalMetadataAccessor();
    MetadataModel.reset(thread);
    InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    TransactionalInMemoryStorageProvider storage =
        new TransactionalInMemoryStorageProvider(boundary);
    CapturingOutbox outbox = CapturingOutbox.inTransaction(boundary).supportingCommands();
    SagaRuntime engine =
        SagaRuntime.builder()
            .register(ParcelSaga.class, ParcelService.class, Parcel.class)
            .register(PrintLabel.class, NotifyCourier.class)
            .storage(storage)
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(new RecordingEventDispatcher())
            .outbox(outbox)
            .handlerInstances(new MapHandlerInstanceProvider().register(new ParcelService()))
            .metadataAccessor(threadLocal ? thread : MetadataAccessor.NONE)
            .build();
    engine.start();

    String sagaId =
        engine
            .receive(new EventDelivery(new ParcelBooked("p-1", 0), null, start))
            .results()
            .get(0)
            .sagaId();
    engine.receive(new EventDelivery(new ParcelPicked("p-1"), null, later));
    OutgoingCommand notify =
        outbox.committed().stream()
            .flatMap(batch -> batch.commands().stream())
            .filter(command -> command.command() instanceof NotifyCourier)
            .findFirst()
            .orElseThrow();
    engine
        .commandReceiver()
        .receive(new OutboxCommand(notify.commandId(), notify.command(), tx -> {}, start));
    engine.receive(new EventDelivery(new ParcelDelivered("p-1"), null, later));

    assertThat(storage.committed().findById(sagaId).orElseThrow().metadata()).isEqualTo(start);
    List<Seen> seen = MetadataModel.SEEN;
    assertThat(seen)
        .extracting(Seen::where)
        .contains("onBooked", "print", "onPicked", "notifyCourier", "onDelivered");
    assertThat(seen).allSatisfy(entry -> assertThat(entry.injected()).isEqualTo(start));
    if (threadLocal) {
      assertThat(seen).allSatisfy(entry -> assertThat(entry.thread()).isEqualTo(start.asMap()));
      assertThat(thread.capture()).isEmpty();
    }
    assertThat(outbox.committed())
        .isNotEmpty()
        .extracting(OutgoingMessages::metadata)
        .containsOnly(start);
  }
}
