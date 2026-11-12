package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInterceptor;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightUnavailable;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.HotelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.Tracker;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TravelDesk;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.Trip;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripRequested;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 36 (.kiro/specs/saga-forge-framework/design.md, specs/009-saga-test-fixture tasks.md T005): for any
 * sequence of events over two trips, an engine whose interceptor only proceeds produces the same handling outcomes,
 * stored instances and handled-command identities as an engine without an interceptor, and the interceptor is called
 * exactly once per handled command.
 */
class CommandInterceptorPropertyTest {

    private static final Instant NOW = Instant.parse("2026-11-12T09:00:00Z");

    /** One engine with its observable state. */
    private static final class Run {

        final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
        final TravelDesk desk = new TravelDesk();
        final Tracker tracker = new Tracker();
        final List<CommandId> intercepted = new ArrayList<>();
        final List<HandlingOutcome> outcomes = new ArrayList<>();
        final List<String> sagaIds = new ArrayList<>();
        final SagaRuntime engine;

        Run(boolean intercepting) {
            AtomicInteger sagas = new AtomicInteger();
            AtomicInteger units = new AtomicInteger();
            SagaRuntime.Builder builder = SagaRuntime.builder()
                    .register(TripSaga.class, TravelDesk.class, Tracker.class, Trip.class)
                    .storage(storage).contextStore(new InMemorySagaContextStore()).transactions(boundary)
                    .dispatcher(new RecordingEventDispatcher())
                    .outbox(CapturingOutbox.inTransaction(boundary).supportingCommands())
                    .handlerInstances(new MapHandlerInstanceProvider().register(desk).register(tracker))
                    .clock(Clock.fixed(NOW, ZoneOffset.UTC))
                    .sagaIds(() -> "saga-" + sagas.incrementAndGet())
                    .unitOfWorkIds(() -> "uow-" + units.incrementAndGet())
                    .retryPause(wait -> { });
            if (intercepting) {
                CommandInterceptor proceeding = CommandInterceptor.proceeding();
                builder.commandInterceptor(invocation -> {
                    intercepted.add(invocation.commandId());
                    proceeding.intercept(invocation);
                });
            }
            engine = builder.build();
            engine.start();
        }

        void receive(Object event) {
            for (SagaHandlingResult result : engine.receive(event).results()) {
                outcomes.add(result.outcome());
                if (result.sagaId() != null && !sagaIds.contains(result.sagaId())) {
                    sagaIds.add(result.sagaId());
                }
            }
        }

        List<Optional<SagaInstance>> instances() {
            return sagaIds.stream().map(id -> storage.committed().findById(id)).toList();
        }

        List<CommandId> handled() {
            List<CommandId> all = new ArrayList<>(desk.identities());
            all.addAll(tracker.identities());
            return all;
        }
    }

    @Provide
    Arbitrary<List<Object>> events() {
        Arbitrary<String> trips = Arbitraries.of("trip-1", "trip-2");
        Arbitrary<Object> event = Arbitraries.integers().between(0, 3).flatMap(kind -> trips.map(trip -> switch (kind) {
            case 0 -> new TripRequested(trip);
            case 1 -> new HotelBooked(trip);
            case 2 -> new FlightBooked(trip);
            default -> new FlightUnavailable(trip);
        }));
        return event.list().ofMinSize(1).ofMaxSize(8);
    }

    @Property(tries = 100)
    void proceedingInterceptorChangesNothing(@ForAll("events") List<Object> events) {
        Run plain = new Run(false);
        Run intercepted = new Run(true);

        for (Object event : events) {
            plain.receive(event);
            intercepted.receive(event);
        }

        assertThat(intercepted.outcomes).isEqualTo(plain.outcomes);
        assertThat(intercepted.sagaIds).isEqualTo(plain.sagaIds);
        assertThat(intercepted.instances()).isEqualTo(plain.instances());
        assertThat(intercepted.handled()).isEqualTo(plain.handled());
        assertThat(intercepted.intercepted).containsExactlyInAnyOrderElementsOf(intercepted.handled());
    }
}
