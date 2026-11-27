package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.Notifier;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.Parcel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelRepository;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelRequested;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.HarvestModel.ParcelSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.harvest.PendingEvents;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * FR-026: a model whose aggregate holds its events runs the same through the fixture in {@code
 * handlers(...)} mode as through a hand-assembled runtime with the same event source: handling
 * outcomes, stored instances and the service handler's calls.
 */
class HarvestParityTest {

  private static final ParcelRequested REQUESTED = new ParcelRequested("p-1");

  /** How a scenario prepares the notifier and the event source before the event. */
  private record Setup(Consumer<Notifier> notifier, Consumer<PendingEvents> source) {}

  private record Observed(
      List<HandlingOutcome> outcomes, Optional<SagaInstance> instance, List<String> notified) {}

  static Stream<Arguments> scenarios() {
    return Stream.of(
        Arguments.of("held events continue the saga", new Setup(n -> {}, s -> {})),
        Arguments.of("transient failure, then retry", new Setup(Notifier::failNext, s -> {})),
        Arguments.of(
            "throwing source fails the unit of work",
            new Setup(n -> {}, s -> s.failNext(new IllegalStateException("down")))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void fixtureMatchesAnAssembledRuntime(String name, Setup setup) {
    Observed fixture = throughFixture(setup);
    Observed assembled = throughAssembledRuntime(setup);

    assertThat(fixture.outcomes()).as("handling outcomes").isEqualTo(assembled.outcomes());
    assertThat(fixture.instance())
        .as("stored instance")
        .isPresent()
        .isEqualTo(assembled.instance());
    assertThat(fixture.notified()).as("service calls").isEqualTo(assembled.notified());
  }

  private static Observed throughFixture(Setup setup) {
    Notifier notifier = new Notifier();
    PendingEvents source = PendingEvents.forParcels();
    setup.notifier().accept(notifier);
    setup.source().accept(source);
    SagaFixture fixture =
        SagaFixture.of(ParcelSaga.class, Parcel.class)
            .handlers(notifier)
            .aggregateEventSource(source)
            .build();

    EventResult result = fixture.when(REQUESTED).result();

    return new Observed(
        outcomes(result.results()),
        fixture.current("saga-1").map(InstanceView::stored),
        notifier.notified());
  }

  private static Observed throughAssembledRuntime(Setup setup) {
    Notifier notifier = new Notifier();
    PendingEvents source = PendingEvents.forParcels();
    setup.notifier().accept(notifier);
    setup.source().accept(source);
    InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    TransactionalInMemoryStorageProvider storage =
        new TransactionalInMemoryStorageProvider(boundary);
    FixtureClock clock = new FixtureClock(FixtureClock.DEFAULT_START);
    AtomicInteger sagaIds = new AtomicInteger();
    AtomicInteger unitOfWorkIds = new AtomicInteger();
    SagaRuntime runtime =
        SagaRuntime.builder()
            .register(ParcelSaga.class, Parcel.class, ParcelRepository.class, Notifier.class)
            .storage(storage)
            .contextStore(new InMemorySagaContextStore())
            .transactions(boundary)
            .dispatcher(new TestTransport())
            .handlerInstances(
                new MapHandlerInstanceProvider()
                    .register(new ParcelRepository())
                    .register(notifier))
            .aggregateEventSource(source)
            .clock(clock)
            .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
            .unitOfWorkIds(() -> "uow-" + unitOfWorkIds.incrementAndGet())
            .retryPause(clock::advance)
            .build();
    runtime.start();

    List<SagaHandlingResult> results = runtime.receive(REQUESTED).results();

    return new Observed(outcomes(results), storage.findById("saga-1"), notifier.notified());
  }

  private static List<HandlingOutcome> outcomes(List<SagaHandlingResult> results) {
    return results.stream().map(SagaHandlingResult::outcome).toList();
  }
}
