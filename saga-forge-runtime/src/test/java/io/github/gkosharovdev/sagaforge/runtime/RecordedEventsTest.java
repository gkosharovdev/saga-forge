package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.AddNote;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.AuditSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.Case;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseClosed;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseEscalated;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseOpened;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.NoteService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.ReviewSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded.Ticket;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded.TicketOpened;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded.TicketSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded.TicketTriaged;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.recorded.TriageService;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;

/**
 * {@code OutgoingMessages.recordedEvents} (specs/008-spring-boot-starter FR-018a, research §8, tasks.md T007): every
 * recorded event in recording order, including those a step of the same instance ran in process; batches made only of
 * recorded events reach outboxes that observe them, and no other outbox.
 */
class RecordedEventsTest {

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();

    private SagaRuntime runtime(Outbox outbox, Class<?>... model) {
        NoteService notes = new NoteService();
        boundary.participant(notes);
        SagaRuntime runtime = SagaRuntime.builder()
                .register(model)
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .outbox(outbox)
                .handlerInstances(new MapHandlerInstanceProvider().register(notes).register(new TriageService()))
                .build();
        runtime.start();
        return runtime;
    }

    @Test
    @DisplayName("recordedEvents holds every recorded event in order; events only those handed on")
    void supersetInOrder() {
        ReviewSaga.failOnClose(false);
        CapturingOutbox outbox = CapturingOutbox.inTransaction(boundary);

        runtime(outbox, AuditSaga.class, ReviewSaga.class, NoteService.class, Case.class)
                .receive(new CaseOpened("case-1", AddNote.Mode.CLOSE_THEN_ESCALATE));

        assertThat(outbox.committed()).singleElement().satisfies(batch -> {
            assertThat(batch.recordedEvents()).containsExactly(new CaseClosed("case-1"), new CaseEscalated("case-1"));
            assertThat(batch.events()).containsExactly(new CaseEscalated("case-1"));
        });
    }

    @Test
    @DisplayName("a unit of work whose events all ran in process reaches an observing outbox only")
    void inProcessOnlyBatch() {
        CapturingOutbox observing = CapturingOutbox.inTransaction(boundary).observingRecordedEvents();
        CapturingOutbox plain = CapturingOutbox.inTransaction(boundary);

        var observed = runtime(observing, TicketSaga.class, TriageService.class, Ticket.class)
                .receive(new TicketOpened("t-1")).results().get(0);
        runtime(plain, TicketSaga.class, TriageService.class, Ticket.class).receive(new TicketOpened("t-2"));

        assertThat(observed.outcome()).isEqualTo(HandlingOutcome.WAITING);
        assertThat(observing.committed()).singleElement().satisfies(batch -> {
            assertThat(batch.events()).isEmpty();
            assertThat(batch.commands()).isEmpty();
            assertThat(batch.recordedEvents()).containsExactly(new TicketTriaged("t-1"));
        });
        assertThat(plain.received()).isEmpty();
    }

    @Test
    @DisplayName("an AFTER_COMMIT default outbox never sees an in-process-only batch")
    void defaultOutboxUnchanged() {
        RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
        SagaRuntime runtime = SagaRuntime.builder().register(TicketSaga.class, TriageService.class, Ticket.class)
                .storage(new TransactionalInMemoryStorageProvider(boundary)).contextStore(new InMemorySagaContextStore())
                .transactions(boundary).dispatcher(dispatcher)
                .handlerInstances(new MapHandlerInstanceProvider().register(new TriageService())).build();
        runtime.start();

        runtime.receive(new TicketOpened("t-3"));

        assertThat(dispatcher.dispatched()).isEmpty();
    }
}
