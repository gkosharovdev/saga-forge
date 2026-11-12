package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.command.NoCommandHandlerException;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInterceptor;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInvocation;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.BookFlight;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.BookHotel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.CancelHotel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.FlightUnavailable;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.HotelBooked;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.NotifyTraveller;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.PingTracker;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TravelDesk;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.Trip;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripRequested;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.interception.TripModel.TripSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;

/**
 * The runtime's obligations towards a {@link CommandInterceptor} ({@code specs/009-saga-test-fixture}
 * contracts/command-interceptor.md, tasks.md T004).
 */
class CommandInterceptorTest {

    private static final String TRIP = "trip-1";

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final CapturingOutbox outbox = CapturingOutbox.inTransaction(boundary).supportingCommands();
    private final TravelDesk desk = new TravelDesk();
    private final List<CommandInvocation> seen = new ArrayList<>();
    private final List<Boolean> transactionActive = new ArrayList<>();

    /** An interceptor that records each invocation and then applies {@code behaviour}. */
    private CommandInterceptor recording(Consumer<CommandInvocation> behaviour) {
        return invocation -> {
            seen.add(invocation);
            transactionActive.add(boundary.current().isPresent());
            behaviour.accept(invocation);
        };
    }

    /** Proceeds when a handler exists, completes the command otherwise. */
    private CommandInterceptor answering() {
        return recording(invocation -> {
            if (invocation.hasHandler()) {
                invocation.proceed();
            }
        });
    }

    private SagaRuntime engine(CommandInterceptor interceptor) {
        SagaRuntime engine = SagaRuntime.builder()
                .register(TripSaga.class, TravelDesk.class, Trip.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .outbox(outbox)
                .handlerInstances(new MapHandlerInstanceProvider().register(desk))
                .retryPause(wait -> { })
                .commandInterceptor(interceptor)
                .build();
        engine.start();
        return engine;
    }

    private static SagaHandlingResult only(SagaRuntime engine, Object event) {
        return engine.receive(event).results().get(0);
    }

    private SagaInstance committed(String sagaId) {
        return storage.committed().findById(sagaId).orElseThrow();
    }

    private List<Object> interceptedCommands() {
        return seen.stream().map(CommandInvocation::command).toList();
    }

    @Test
    @DisplayName("obligation 1: called once per handling of step, outbox-delivered and non-saga commands")
    void oncePerHandling() {
        SagaRuntime engine = engine(answering());

        String sagaId = only(engine, new TripRequested(TRIP)).sagaId();
        only(engine, new HotelBooked(TRIP));
        assertThat(only(engine, new FlightBooked(TRIP)).outcome()).isEqualTo(HandlingOutcome.COMPLETED);

        assertThat(interceptedCommands()).as("the outbox command is not intercepted when stored")
                .containsExactly(new BookHotel(TRIP), new PingTracker(TRIP), new BookFlight(TRIP));
        OutgoingCommand stored = outbox.committed().stream().flatMap(batch -> batch.commands().stream())
                .findFirst().orElseThrow();
        engine.commandReceiver().receive(new OutboxCommand(stored.commandId(), stored.command(), tx -> { }));
        engine.commandGateway().send(new BookHotel("trip-x"));

        assertThat(interceptedCommands()).containsExactly(new BookHotel(TRIP), new PingTracker(TRIP),
                new BookFlight(TRIP), new NotifyTraveller(TRIP), new BookHotel("trip-x"));
        assertThat(seen.get(0).commandId()).isEqualTo(
                CommandId.of(sagaId, TripModel.START, BookHotel.class.getName(), 1));
        assertThat(seen.get(3).commandId()).isEqualTo(stored.commandId());
        assertThat(seen.get(4).commandId().sagaScoped()).isFalse();
        assertThat(desk.handled()).containsExactly(new BookHotel(TRIP), new BookFlight(TRIP),
                new NotifyTraveller(TRIP), new BookHotel("trip-x"));
    }

    @Test
    @DisplayName("obligation 1: a compensation's command is intercepted with the compensation as its step")
    void compensationCommands() {
        SagaRuntime engine = engine(answering());
        String sagaId = only(engine, new TripRequested(TRIP)).sagaId();
        only(engine, new HotelBooked(TRIP));

        assertThat(only(engine, new FlightUnavailable(TRIP)).outcome()).isEqualTo(HandlingOutcome.COMPENSATED);

        assertThat(seen.get(seen.size() - 1).command()).isEqualTo(new CancelHotel(sagaId));
        assertThat(seen.get(seen.size() - 1).commandId().stepName()).isEqualTo(TripModel.CANCEL);
    }

    @Test
    @DisplayName("obligation 2: runs inside the unit of work's transaction with the identity bound for the handler")
    void insideTheUnitOfWork() {
        SagaRuntime engine = engine(answering());

        only(engine, new TripRequested(TRIP));
        engine.commandGateway().send(new BookHotel("trip-x"));

        assertThat(transactionActive).containsOnly(true);
        assertThat(desk.identities()).containsExactly(seen.get(0).commandId(), seen.get(2).commandId());
    }

    @Test
    @DisplayName("obligation 3: a transient exception of the interceptor is retried like the handler's")
    void transientIsRetried() {
        int[] failures = {2};
        SagaRuntime engine = engine(recording(invocation -> {
            if (invocation.command() instanceof BookFlight && failures[0]-- > 0) {
                throw new Busy();
            }
            if (invocation.hasHandler()) {
                invocation.proceed();
            }
        }));
        String sagaId = only(engine, new TripRequested(TRIP)).sagaId();

        assertThat(only(engine, new HotelBooked(TRIP)).outcome()).isEqualTo(HandlingOutcome.WAITING);

        assertThat(committed(sagaId).steps().get(TripModel.HOTEL).attempts()).isEqualTo(3);
        assertThat(seen.stream().filter(invocation -> invocation.command() instanceof BookFlight)
                .map(CommandInvocation::commandId).distinct()).hasSize(1);
        assertThat(desk.handled()).containsOnlyOnce(new BookFlight(TRIP));
    }

    @Test
    @DisplayName("obligation 3: a final exception of the interceptor compensates the committed steps")
    void finalCompensates() {
        SagaRuntime engine = engine(recording(invocation -> {
            if (invocation.command() instanceof BookFlight) {
                throw new Refused();
            }
            if (invocation.hasHandler()) {
                invocation.proceed();
            }
        }));
        String sagaId = only(engine, new TripRequested(TRIP)).sagaId();

        SagaHandlingResult result = only(engine, new HotelBooked(TRIP));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.COMPENSATED);
        assertThat(committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(committed(sagaId).compensationTrigger().kind()).isEqualTo(CompensationTriggerKind.FINAL_FAILURE);
        assertThat(desk.handled()).contains(new CancelHotel(sagaId));
    }

    @Test
    @DisplayName("obligation 3: an unclassified exception of the interceptor ends the saga FAILED")
    void unclassifiedFails() {
        IllegalStateException boom = new IllegalStateException("boom");
        SagaRuntime engine = engine(recording(invocation -> {
            throw boom;
        }));

        SagaHandlingResult result = only(engine, new TripRequested(TRIP));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().causeType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(desk.handled()).isEmpty();
    }

    @Test
    @DisplayName("obligation 4: returning without proceed() counts as handled and records the identity")
    void completedWithoutHandler() {
        SagaRuntime engine = engine(recording(invocation -> { }));

        SagaHandlingResult result = only(engine, new TripRequested(TRIP));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(desk.handled()).isEmpty();
        assertThat(committed(result.sagaId()).steps().get(TripModel.START).handledCommands())
                .containsExactly(seen.get(0).commandId(), seen.get(1).commandId());
    }

    @Test
    @DisplayName("obligation 5 and hasHandler(): proceed() runs once; without a route it throws as before")
    void proceedOnce() {
        List<Throwable> secondCalls = new ArrayList<>();
        List<Boolean> handlers = new ArrayList<>();
        SagaRuntime engine = engine(recording(invocation -> {
            handlers.add(invocation.hasHandler());
            if (invocation.hasHandler()) {
                invocation.proceed();
                try {
                    invocation.proceed();
                } catch (IllegalStateException expected) {
                    secondCalls.add(expected);
                }
            }
        }));

        only(engine, new TripRequested(TRIP));

        assertThat(handlers).containsExactly(true, false);
        assertThat(secondCalls).hasSize(1);
        assertThat(desk.handled()).containsExactly(new BookHotel(TRIP));
    }

    @Test
    @DisplayName("obligation 6: the default interceptor fails a command without a handler as before")
    void defaultProceeds() {
        SagaRuntime engine = engine(CommandInterceptor.proceeding());

        SagaHandlingResult result = only(engine, new TripRequested(TRIP));

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().causeType()).isEqualTo(NoCommandHandlerException.class.getName());
    }

    @Test
    @DisplayName("the builder takes at most one interceptor and rejects null")
    void builderRules() {
        SagaRuntime.Builder builder = SagaRuntime.builder().commandInterceptor(CommandInterceptor.proceeding());

        assertThatThrownBy(() -> builder.commandInterceptor(CommandInterceptor.proceeding()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SagaRuntime.builder().commandInterceptor(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Busy for now: retrying may help. */
    private static final class Busy extends RuntimeException implements TransientFailure {

        Busy() {
            super("busy");
        }
    }

    /** Refused for good. */
    private static final class Refused extends RuntimeException implements FinalFailure {

        Refused() {
            super("refused");
        }
    }
}
