package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.OutboxCommand;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.BillingSaga;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.CardService;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.ChargeCard;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.Invoice;
import io.github.gkosharovdev.sagaforge.engine.fixtures.outbox.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.engine.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Property 35 (.kiro/specs/saga-forge-framework/design.md, specs/007 tasks.md T059): for any number of transient
 * failures of the sending step, whether it finally commits or rolls back, and any number of redeliveries through
 * {@code CommandReceiver}: the handler never runs before the commit, never after a rollback, the command is stored once
 * per committed step, and every handling sees the identity assigned at send time.
 */
class OutboxCommandPropertyTest {

    @Property(tries = 60)
    void outboxCommandsFollowTheirUnitOfWork(@ForAll @IntRange(min = 0, max = 6) int transientFailures,
            @ForAll boolean failAfterSend, @ForAll @IntRange(min = 0, max = 3) int redeliveries) {
        BillingSaga.reset();
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        CardService cards = new CardService();
        CapturingOutbox outbox = CapturingOutbox.inTransaction(boundary).supportingCommands();
        SagaEngine engine = SagaEngine.builder().register(BillingSaga.class, CardService.class, Invoice.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new InMemorySagaContextStore()).transactions(boundary)
                .dispatcher(new RecordingEventDispatcher()).outbox(outbox)
                .handlerInstances(new MapHandlerInstanceProvider().register(cards)).build();
        engine.start();

        SagaHandlingResult result = engine.receive(new InvoiceIssued("i-1", failAfterSend, transientFailures))
                .results().get(0);

        assertThat(cards.charged()).as("never handled inside the sending unit of work").isEmpty();
        List<OutgoingCommand> stored = outbox.committed().stream().flatMap(b -> b.commands().stream()).toList();
        boolean committed = !failAfterSend && transientFailures < 5;
        if (!committed) {
            assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
            assertThat(stored).as("nothing from a rolled-back unit of work").isEmpty();
            return;
        }
        CommandId assigned = CommandId.of(result.sagaId(), BillingSaga.START_STEP, ChargeCard.class.getName(), 1);
        assertThat(stored).singleElement().satisfies(c -> assertThat(c.commandId()).isEqualTo(assigned));
        for (int i = 0; i <= redeliveries; i++) {
            engine.commandReceiver().receive(new OutboxCommand(assigned, stored.get(0).command(), tx -> { }));
        }
        assertThat(cards.charged()).hasSize(redeliveries + 1).containsOnly(assigned);
    }
}
