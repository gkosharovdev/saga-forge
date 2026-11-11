package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.BillingSaga;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.CardCharged;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.CardService;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.ChargeCard;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.Invoice;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.engine.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;

/**
 * Outbox commands in the engine (specs/007-in-memory-transport FR-010 to FR-013, research §3, tasks.md T058):
 * stored with the sending unit of work, never handled before its commit or after its rollback, handled through
 * {@code CommandReceiver} with the identity assigned at send time.
 */
class OutboxCommandTest {

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final CardService cards = new CardService();
    private final CapturingOutbox outbox = CapturingOutbox.inTransaction(boundary).supportingCommands();

    @BeforeEach
    void setUp() {
        BillingSaga.reset();
    }

    private SagaEngine engine(Outbox engineOutbox) {
        SagaEngine engine = SagaEngine.builder()
                .register(BillingSaga.class, CardService.class, Invoice.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .outbox(engineOutbox)
                .handlerInstances(new MapHandlerInstanceProvider().register(cards))
                .build();
        engine.start();
        return engine;
    }

    private List<OutgoingCommand> committedCommands() {
        return outbox.committed().stream().flatMap(batch -> batch.commands().stream()).toList();
    }

    @Test
    @DisplayName("scenario 1: a outbox command is stored with the committed batch and not handled in the step")
    void storedNotHandled() {
        SagaHandlingResult result = engine(outbox).receive(new InvoiceIssued("i-1")).results().get(0);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(cards.charged()).isEmpty();
        assertThat(committedCommands()).singleElement().satisfies(command -> {
            assertThat(command.command()).isEqualTo(new ChargeCard("i-1"));
            assertThat(command.commandId())
                    .isEqualTo(CommandId.of(result.sagaId(), BillingSaga.START_STEP, ChargeCard.class.getName(), 1));
        });
        assertThat(outbox.committed().get(0).events()).isEmpty();
        assertThat(storage.committed().findById(result.sagaId()).orElseThrow().steps().get(BillingSaga.START_STEP)
                .handledCommands()).containsExactly(committedCommands().get(0).commandId());
    }

    @Test
    @DisplayName("scenario 2: a step that rolls back stores no command")
    void rollbackStoresNothing() {
        SagaHandlingResult result = engine(outbox).receive(new InvoiceIssued("i-1", true, 0)).results().get(0);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(outbox.received()).isEmpty();
        assertThat(cards.charged()).isEmpty();
    }

    @Test
    @DisplayName("scenario 3: CommandReceiver handles it with the original CommandId, in its own unit of work")
    void receivedWithOriginalIdentity() {
        SagaEngine engine = engine(outbox);
        engine.receive(new InvoiceIssued("i-1"));
        OutgoingCommand sent = committedCommands().get(0);
        List<Optional<LocalTransaction>> seenTransactions = new ArrayList<>();

        engine.commandReceiver().receive(new OutboxCommand(sent.commandId(), sent.command(),
                tx -> seenTransactions.add(boundary.current())));

        assertThat(cards.charged()).containsExactly(sent.commandId());
        assertThat(seenTransactions).singleElement().satisfies(tx -> assertThat(tx).isPresent());
        assertThat(outbox.committed().get(1).sagaType()).isNull();
        assertThat(outbox.committed().get(1).events()).containsExactly(new CardCharged("i-1"));
    }

    @Test
    @DisplayName("a failing handler or in-transaction callback rolls back and propagates")
    void failuresRollBack() {
        SagaEngine engine = engine(outbox);
        engine.receive(new InvoiceIssued("i-1"));
        OutgoingCommand sent = committedCommands().get(0);
        IllegalStateException handlerFailure = new IllegalStateException("provider down");
        cards.failNext(handlerFailure);
        IllegalStateException markFailure = new IllegalStateException("mark failed");

        assertThatThrownBy(() -> engine.commandReceiver().receive(new OutboxCommand(sent.commandId(), sent.command(),
                tx -> { }))).isSameAs(handlerFailure);
        assertThatThrownBy(() -> engine.commandReceiver().receive(new OutboxCommand(sent.commandId(), sent.command(),
                tx -> {
                    throw markFailure;
                }))).isSameAs(markFailure);
        assertThat(outbox.committed()).hasSize(1);
    }

    @Test
    @DisplayName("a retried step stores the command once, with the same identity")
    void retriedStepStoresOnce() {
        SagaHandlingResult result = engine(outbox).receive(new InvoiceIssued("i-1", false, 2)).results().get(0);

        assertThat(result.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(committedCommands()).singleElement().satisfies(command -> assertThat(command.commandId())
                .isEqualTo(CommandId.of(result.sagaId(), BillingSaga.START_STEP, ChargeCard.class.getName(), 1)));
    }

    @Test
    @DisplayName("FR-010a: a outbox command sent outside any unit of work is stored in its own committed batch")
    void sentOutsideUnitOfWork() {
        engine(outbox).commandGateway().send(new ChargeCard("i-9"));

        assertThat(cards.charged()).isEmpty();
        assertThat(outbox.committed()).singleElement().satisfies(batch -> {
            assertThat(batch.sagaType()).isNull();
            assertThat(batch.events()).isEmpty();
            assertThat(batch.commands()).singleElement().satisfies(command -> {
                assertThat(command.command()).isEqualTo(new ChargeCard("i-9"));
                assertThat(command.commandId().sagaScoped()).isFalse();
            });
        });
    }

    @Test
    @DisplayName("the engine refuses to start when a OUTBOX command meets an outbox that cannot store commands")
    void refusedWithoutCommandOutbox() {
        assertThatThrownBy(() -> engine(CapturingOutbox.inTransaction(boundary)))
                .isInstanceOf(SagaEngineStartupException.class)
                .satisfies(e -> assertThat(((SagaEngineStartupException) e).report().allErrors())
                        .anySatisfy(error -> assertThat(error).contains("ChargeCard").contains("through the outbox")));
    }

    @Test
    @DisplayName("CommandReceiver rejects a call from inside a unit of work and an unstarted engine")
    void receiverPreconditions() {
        SagaEngine unstarted = SagaEngine.builder().register(BillingSaga.class, CardService.class, Invoice.class)
                .storage(storage).contextStore(new InMemorySagaContextStore()).transactions(boundary)
                .dispatcher(new RecordingEventDispatcher()).outbox(outbox)
                .handlerInstances(new MapHandlerInstanceProvider().register(cards)).build();
        CommandId id = CommandId.of("s", BillingSaga.START_STEP, ChargeCard.class.getName(), 1);

        assertThatThrownBy(() -> unstarted.commandReceiver().receive(new OutboxCommand(id, new ChargeCard("i"),
                tx -> { }))).isInstanceOf(IllegalStateException.class);
    }
}
