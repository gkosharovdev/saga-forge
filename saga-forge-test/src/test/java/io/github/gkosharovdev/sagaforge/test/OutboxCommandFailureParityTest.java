package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInterceptor;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.CollectParcel;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.CourierRefused;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.CourierSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.CourierService;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.DeliveryBooked;
import io.github.gkosharovdev.sagaforge.test.fixtures.Courier.PickupScheduled;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A failing outbox command gives the same stored instance, committed commands and outcome events through the fixture
 * as through the shipped runtime over the in-memory transport, whose relay classifies and reports the failure
 * ({@code specs/012-outbox-command-failure-feedback} contracts/fixture.md, tasks.md T040). The fixture's
 * {@code outboxCommandAttempts} plays the transport's {@code maxAttempts}.
 */
class OutboxCommandFailureParityTest {

    private static final int ATTEMPTS = 3;
    private static final Duration WAIT = Duration.ofSeconds(10);

    /** {@code times} failures of {@link CollectParcel}, or always if negative. */
    private record Failure(Supplier<? extends RuntimeException> exception, int times) {
    }

    private record Sent(Object command, CommandId commandId) {
    }

    private record Observed(SagaInstance instance, List<Sent> commands, List<SagaOutcome> outcomeEvents) {
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of("final failure compensates", new Failure(CourierRefused::new, -1)),
                Arguments.of("exhausted after the attempts compensates",
                        new Failure(() -> new IllegalStateException("courier busy"), -1)),
                Arguments.of("two failures, then a success",
                        new Failure(() -> new IllegalStateException("courier busy"), 2)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void fixtureMatchesTheInMemoryTransport(String name, Failure failure) {
        Observed fixture = throughFixture(failure);
        Observed transport = throughInMemoryTransport(failure);

        assertThat(fixture.instance()).as("stored instance").isEqualTo(transport.instance());
        assertThat(fixture.commands()).as("committed commands").isEqualTo(transport.commands());
        assertThat(fixture.outcomeEvents()).as("outcome events").isEqualTo(transport.outcomeEvents());
    }

    private static Observed throughFixture(Failure failure) {
        SagaFixture.Builder builder = SagaFixture.of(CourierSaga.class).register(CourierService.class)
                .outboxCommandAttempts(ATTEMPTS);
        if (failure.times() < 0) {
            builder.failCommand(CollectParcel.class, failure.exception());
        } else {
            builder.failCommand(CollectParcel.class, failure.exception(), failure.times());
        }
        SagaFixture fixture = builder.build();
        List<SagaOutcome> outcomeEvents = new ArrayList<>();
        String sagaId = fixture.when(new DeliveryBooked("d-1")).result().results().get(0).sagaId();
        outcomeEvents.addAll(fixture.when(new PickupScheduled("d-1")).result().outcomeEvents());
        List<Sent> commands = fixture.commands().stream()
                .map(command -> new Sent(command.command(), command.commandId())).toList();
        return new Observed(fixture.current(sagaId).orElseThrow().stored(), commands, outcomeEvents);
    }

    private static Observed throughInMemoryTransport(Failure failure) {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
        InMemoryMessageStore log = new InMemoryMessageStore();
        FixtureClock clock = new FixtureClock(FixtureClock.DEFAULT_START);
        List<Sent> committed = new ArrayList<>();
        AtomicInteger remaining = new AtomicInteger(failure.times());
        CommandInterceptor failing = invocation -> {
            if (invocation.command() instanceof CollectParcel && remaining.getAndDecrement() != 0) {
                throw failure.exception().get();
            }
            Sent sent = new Sent(invocation.command(), invocation.commandId());
            boundary.current().flatMap(tx -> tx.resource(InMemoryTransactionBoundary.Scope.class))
                    .orElseThrow().onCommit(() -> committed.add(sent));
        };
        InMemoryTransport transport = InMemoryTransport.builder().messageStore(log).transactions(boundary)
                .settings(TransportSettings.defaults().withWorkers(1).withMaxAttempts(ATTEMPTS)
                        .withBackoff(Duration.ofMillis(1), Duration.ofMillis(4))
                        .withPollInterval(Duration.ofMillis(10)))
                .build();
        AtomicInteger sagaIds = new AtomicInteger();
        AtomicInteger unitOfWorkIds = new AtomicInteger();
        SagaRuntime runtime = SagaRuntime.builder()
                .register(CourierSaga.class, CourierService.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(transport.dispatcher())
                .outbox(transport.outbox())
                .handlerInstances(new MapHandlerInstanceProvider().register(new CourierService()))
                .clock(clock)
                .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                .unitOfWorkIds(() -> "uow-" + unitOfWorkIds.incrementAndGet())
                .commandInterceptor(failing)
                .build();
        runtime.start();
        transport.start(runtime, runtime.commandReceiver());
        try {
            String sagaId = runtime.receive(new DeliveryBooked("d-1")).results().get(0).sagaId();
            runtime.receive(new PickupScheduled("d-1"));
            // The set-aside commits with the reaction's first unit of work; the compensations follow it.
            TransportHarness.await(() -> !log.all().isEmpty() && log.all().stream()
                    .allMatch(message -> message.status() != MessageStatus.UNCONSUMED)
                    && storage.committed().findById(sagaId).orElseThrow().status() != SagaStatus.COMPENSATING,
                    WAIT, "a quiet log and no compensation running");
            List<SagaOutcome> outcomeEvents = log.all().stream().map(message -> message.payload())
                    .filter(SagaOutcome.class::isInstance).map(SagaOutcome.class::cast).toList();
            return new Observed(storage.committed().findById(sagaId).orElseThrow(), List.copyOf(committed),
                    outcomeEvents);
        } finally {
            transport.stop(Duration.ofSeconds(5));
        }
    }
}
