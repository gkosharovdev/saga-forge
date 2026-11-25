package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInterceptor;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking.BookingRequested;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking.BookingSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;

/**
 * A deadline that comes due gives the same stored instance, committed commands and outcome events through the fixture
 * as through the shipped runtime over the in-memory transport, with one clock advanced by the same amount
 * ({@code specs/017-saga-deadlines} contracts/fixture.md, tasks.md T041).
 */
class DeadlineParityTest {

    private static final Duration HOLD = Duration.ofMinutes(30);

    private record Sent(Object command, CommandId commandId) {
    }

    private record Observed(SagaInstance instance, List<Sent> commands, List<SagaOutcome> outcomeEvents) {
    }

    @Test
    @DisplayName("an expired hold compensates the same way in the fixture and over the in-memory transport")
    void fixtureMatchesTheInMemoryTransport() {
        Observed fixture = throughFixture();
        Observed transport = throughInMemoryTransport();

        assertThat(fixture.instance().status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(fixture.instance()).as("stored instance").isEqualTo(transport.instance());
        assertThat(fixture.commands()).as("committed commands").isEqualTo(transport.commands());
        assertThat(fixture.outcomeEvents()).as("outcome events").isEqualTo(transport.outcomeEvents());
    }

    private static Observed throughFixture() {
        SagaFixture fixture = SagaFixture.of(BookingSaga.class, Booking.Seat.class).build();
        String sagaId = fixture.when(new BookingRequested("b-1", HOLD.toString())).result().results().get(0).sagaId();
        List<SagaOutcome> outcomeEvents = fixture.whenTimeElapses(HOLD).result().outcomeEvents();
        List<Sent> commands = fixture.commands().stream()
                .map(command -> new Sent(command.command(), command.commandId())).toList();
        return new Observed(fixture.current(sagaId).orElseThrow().stored(), commands, outcomeEvents);
    }

    private static Observed throughInMemoryTransport() {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
        InMemoryMessageStore log = new InMemoryMessageStore();
        FixtureClock clock = new FixtureClock(FixtureClock.DEFAULT_START);
        List<Sent> committed = new ArrayList<>();
        CommandInterceptor recording = invocation -> {
            Sent sent = new Sent(invocation.command(), invocation.commandId());
            boundary.current().flatMap(tx -> tx.resource(InMemoryTransactionBoundary.Scope.class))
                    .orElseThrow().onCommit(() -> committed.add(sent));
        };
        InMemoryTransport transport = InMemoryTransport.builder().messageStore(log).transactions(boundary)
                .clock(clock).settings(TransportSettings.defaults().withWorkers(1)
                        .withPollInterval(Duration.ofMillis(10)))
                .build();
        AtomicInteger sagaIds = new AtomicInteger();
        AtomicInteger unitOfWorkIds = new AtomicInteger();
        SagaRuntime runtime = SagaRuntime.builder()
                .register(BookingSaga.class, Booking.Seat.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(transport.dispatcher())
                .outbox(transport.outbox())
                .clock(clock)
                .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                .unitOfWorkIds(() -> "uow-" + unitOfWorkIds.incrementAndGet())
                .commandInterceptor(recording)
                .build();
        runtime.start();
        transport.start(runtime, runtime.commandReceiver());
        try {
            String sagaId = runtime.receive(new BookingRequested("b-1", HOLD.toString())).results().get(0).sagaId();
            clock.advance(HOLD);
            TransportHarness.await(() -> storage.committed().findById(sagaId).orElseThrow().status()
                    == SagaStatus.COMPENSATED, Duration.ofSeconds(10), "the hold to lapse");
            List<SagaOutcome> outcomeEvents = log.all().stream().map(message -> message.payload())
                    .filter(SagaOutcome.class::isInstance).map(SagaOutcome.class::cast).toList();
            return new Observed(storage.committed().findById(sagaId).orElseThrow(), List.copyOf(committed),
                    outcomeEvents);
        } finally {
            transport.stop(Duration.ofSeconds(5));
        }
    }
}
