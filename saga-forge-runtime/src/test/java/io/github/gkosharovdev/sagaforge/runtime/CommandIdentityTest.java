package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency.Charge;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency.ChargeSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency.CreateShipment;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency.IdempotencyFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency.LabelShipment;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency.Notify;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency.Shipment;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stable command identities handed to every handler form (specs/005-step-retry User Story 1, FR-001 to FR-005,
 * contracts/command-identity.md, tasks.md T024).
 */
class CommandIdentityTest {

    private static final String ORDER = "order-1";

    private final IdempotencyFixture fixture = new IdempotencyFixture();

    @Test
    @DisplayName("each command gets a saga-scoped identity numbered per type within the step run")
    void ordinalsPerType() {
        SagaHandlingResult placed = fixture.place(ORDER, 100);

        assertThat(placed.outcome()).isEqualTo(HandlingOutcome.WAITING);
        String sagaId = placed.sagaId();
        assertThat(fixture.charges().seen()).containsExactly(
                CommandId.of(sagaId, ChargeSaga.PLACED_STEP, Charge.class.getName(), 1),
                CommandId.of(sagaId, ChargeSaga.PLACED_STEP, Charge.class.getName(), 2));
        assertThat(fixture.notifications().seen()).containsExactly(
                CommandId.of(sagaId, ChargeSaga.PLACED_STEP, Notify.class.getName(), 1));
        assertThat(fixture.provider().charges()).hasSize(2);
    }

    @Test
    @DisplayName("the committed step records the identities it handled, in dispatch order")
    void recordedOnTheStep() {
        String sagaId = fixture.place(ORDER, 100).sagaId();

        StepRecord placed = fixture.committed(sagaId).steps().get(ChargeSaga.PLACED_STEP);
        assertThat(placed.attempts()).isEqualTo(1);
        assertThat(placed.handledCommands()).containsExactly(fixture.charges().seen().get(0),
                fixture.charges().seen().get(1), fixture.notifications().seen().get(0));
        assertThat(fixture.committed(sagaId).steps().get(ChargeSaga.PACKED_STEP).handledCommands()).isEmpty();
    }

    @Test
    @DisplayName("a unit of work run again after a rollback hands the creation and instance handlers the same "
            + "identities")
    void stableAcrossARerun() {
        String sagaId = fixture.place(ORDER, 100).sagaId();
        fixture.storage().conflictOnNextSave(sagaId);

        assertThat(fixture.pack(ORDER).outcome()).isEqualTo(HandlingOutcome.NOT_HANDLED);
        assertThat(fixture.pack(ORDER).outcome()).isEqualTo(HandlingOutcome.WAITING);

        List<Shipment> saves = fixture.shipments().saves();
        assertThat(saves).hasSize(4);
        CommandId created = CommandId.of(sagaId, ChargeSaga.PACKED_STEP, CreateShipment.class.getName(), 1);
        CommandId labelled = CommandId.of(sagaId, ChargeSaga.PACKED_STEP, LabelShipment.class.getName(), 1);
        assertThat(saves).extracting(Shipment::createdBy).containsOnly(created);
        assertThat(fixture.shipments().shipments().get(Shipment.shipmentIdFor(ORDER)).labelledBy())
                .isEqualTo(labelled);
        assertThat(fixture.committed(sagaId).steps().get(ChargeSaga.PACKED_STEP).handledCommands())
                .containsExactly(created, labelled);
    }

    @Test
    @DisplayName("two instances get different identities for the same step and command")
    void differentInstances() {
        fixture.place("order-1", 100);
        fixture.place("order-2", 100);

        assertThat(fixture.charges().seen()).extracting(CommandId::value).doesNotHaveDuplicates().hasSize(4);
        assertThat(fixture.provider().charges()).hasSize(4);
    }

    @Test
    @DisplayName("a command sent outside a saga step gets a fresh unscoped identity")
    void outsideASaga() {
        fixture.engine().commandGateway().send(new Notify("order-x"));
        fixture.engine().commandGateway().send(new Notify("order-x"));

        assertThat(fixture.notifications().seen()).hasSize(2).allSatisfy(id -> {
            assertThat(id.sagaScoped()).isFalse();
            assertThat(id.commandType()).isEqualTo(Notify.class.getName());
        });
        assertThat(fixture.notifications().seen().get(0).value())
                .isNotEqualTo(fixture.notifications().seen().get(1).value());
    }

    /** A command for {@link TwoIdentities}. */
    @Command
    public record Stamp(String id) {
    }

    /** A handler that declares the command identity twice. */
    public static final class TwoIdentities {

        @CommandHandler
        public void handle(Stamp command, CommandId first, CommandId second) {
        }
    }

    @Test
    @DisplayName("a handler declaring more than one CommandId parameter is refused at startup")
    void moreThanOneIdentityParameter() {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        SagaRuntime engine = SagaRuntime.builder()
                .register(TwoIdentities.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .handlerInstances(new MapHandlerInstanceProvider().register(new TwoIdentities()))
                .build();

        SagaRuntimeStartupException refused = catchThrowableOfType(SagaRuntimeStartupException.class, engine::start);

        assertThat(refused).isNotNull();
        assertThat(refused.report().engineErrors()).containsExactly(
                "@CommandHandler TwoIdentities.handle declares more than one CommandId parameter: #2, #3");
    }
}
