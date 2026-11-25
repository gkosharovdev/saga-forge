package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.schedule.Shipment;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.schedule.ShipmentCommands;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.schedule.ShipmentEvents;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.schedule.ShipmentSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.SchedulingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.SchedulingOutbox.State;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 4: {@code CommandGateway.schedule} and {@code cancel} (specs/017-saga-deadlines spec.md US4, FR-007;
 * contracts/outbox-and-gateway.md "CommandGateway"; tasks.md T034).
 */
class ScheduledCommandTest {

    private static final Instant AT = Instant.parse("2026-02-01T08:00:00Z");

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
    private final ShipmentCommands commands = new ShipmentCommands();
    private final AtomicInteger unitsOfWork = new AtomicInteger();

    private SagaRuntime runtime(Outbox outbox) {
        SagaRuntime runtime = SagaRuntime.builder()
                .register(ShipmentSaga.class, ShipmentCommands.class, Shipment.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(contexts)
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .outbox(outbox)
                .handlerInstances(new MapHandlerInstanceProvider().register(commands))
                .sagaIds(() -> "saga-1")
                .unitOfWorkIds(() -> "uow-" + unitsOfWork.incrementAndGet())
                .build();
        runtime.start();
        return runtime;
    }

    private String context(String key) {
        return contexts.find("saga-1").orElseThrow().values().get(key).toString();
    }

    @Test
    @DisplayName("a step schedules an outbox command: stored with the batch, due at 'at', saga-scoped CommandId")
    void schedules() {
        SchedulingOutbox outbox = new SchedulingOutbox(boundary).supportingCommands();
        SagaRuntime runtime = runtime(outbox);

        runtime.receive(new ShipmentEvents.Booked("s-1", AT, false));

        ScheduledMessage message = outbox.scheduled().get(0);
        assertThat(message.messageId()).isEqualTo(context("scheduled")).matches("uow-\\d+:scheduled:1");
        assertThat(message.kind()).isEqualTo(MessageKind.COMMAND);
        assertThat(message.notBefore()).isEqualTo(AT);
        assertThat(message.payload()).isEqualTo(new ShipmentCommands.Ship("s-1"));
        assertThat(message.commandId().sagaScoped()).isTrue();
        assertThat(message.commandId().stepName()).isEqualTo("onBooked");
        assertThat(commands.handled()).isEmpty();
    }

    @Test
    @DisplayName("cancelling in the same unit of work drops it from the batch and returns true")
    void cancelInSameUnitOfWork() {
        SchedulingOutbox outbox = new SchedulingOutbox(boundary).supportingCommands();

        runtime(outbox).receive(new ShipmentEvents.Booked("s-1", AT, true));

        assertThat(context("cancelledAtOnce")).isEqualTo("true");
        assertThat(outbox.scheduled()).isEmpty();
    }

    @Test
    @DisplayName("cancelling a stored pending command in a later unit of work cancels it in its transaction")
    void cancelLater() {
        SchedulingOutbox outbox = new SchedulingOutbox(boundary).supportingCommands();
        SagaRuntime runtime = runtime(outbox);
        runtime.receive(new ShipmentEvents.Booked("s-1", AT, false));

        runtime.receive(new ShipmentEvents.Withdrawn("s-1"));

        assertThat(context("withdrawn")).isEqualTo("true");
        assertThat(outbox.state(context("scheduled"))).isEqualTo(State.CANCELLED);
    }

    @Test
    @DisplayName("cancelling a command already being delivered returns false")
    void cancelLeased() {
        SchedulingOutbox outbox = new SchedulingOutbox(boundary).supportingCommands();
        SagaRuntime runtime = runtime(outbox);
        runtime.receive(new ShipmentEvents.Booked("s-1", AT, false));
        outbox.lease(context("scheduled"));

        runtime.receive(new ShipmentEvents.Withdrawn("s-1"));

        assertThat(context("withdrawn")).isEqualTo("false");
    }

    @Test
    @DisplayName("scheduling a command that is not an outbox command fails the step")
    void notAnOutboxCommand() {
        SchedulingOutbox outbox = new SchedulingOutbox(boundary).supportingCommands();
        SagaRuntime runtime = runtime(outbox);
        runtime.receive(new ShipmentEvents.Booked("s-1", AT, false));

        SagaHandlingResult result = runtime.receive(new ShipmentEvents.Misused("s-1")).results().get(0);

        assertThat(result.failure().causeType()).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(result.failure().message()).contains("not @Command(delivery = OUTBOX)");
    }

    @Test
    @DisplayName("over an outbox that cannot schedule, schedule throws UnsupportedOperationException")
    void unsupported() {
        SagaRuntime runtime = runtime(CapturingOutbox.inTransaction(boundary).supportingCommands());

        SagaHandlingResult result = runtime.receive(new ShipmentEvents.Booked("s-1", AT, false)).results().get(0);

        assertThat(result.failure().causeType()).isEqualTo(UnsupportedOperationException.class.getName());
        assertThat(result.failure().message()).contains("cannot schedule messages");
    }

    @Test
    @DisplayName("outside a unit of work, schedule opens one of its own")
    void outsideUnitOfWork() {
        SchedulingOutbox outbox = new SchedulingOutbox(boundary).supportingCommands();
        SagaRuntime runtime = runtime(outbox);

        runtime.commandGateway().schedule(new ShipmentCommands.Ship("s-9"), AT);

        assertThat(outbox.scheduled()).singleElement()
                .satisfies(message -> assertThat(message.commandId().sagaScoped()).isFalse());
        assertThatThrownBy(() -> runtime.commandGateway().schedule(new ShipmentCommands.Ship("s-9"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
