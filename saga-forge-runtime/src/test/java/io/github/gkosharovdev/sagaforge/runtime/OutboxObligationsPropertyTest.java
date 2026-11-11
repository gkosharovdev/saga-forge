package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox.Timing;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.Fee;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FailingHandlerInstances;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FailureSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FailureSagaObjects;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FaultPlan;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FaultPoint;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FeeSchedule;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FeeScheduleResolver;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.FeeService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.Label;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.LabelPrinted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.LabelResolver;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.Parcel;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.ParcelRequested;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.Slot;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.failure.SlotRepository;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.AddNote;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.AuditSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.Case;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseClosed;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseEscalated;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseNoted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.CaseOpened;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.NoteService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.ReviewSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 26: under either outbox timing, each committed unit of work's batch holds exactly its buffered events,
 * once, in recording order, under a {@code unitOfWorkId} no other batch has, with {@code sagaType}/{@code sagaId}
 * present iff saga-scoped; rolled-back units of work leave nothing.
 *
 * <p>**Validates: Requirements FR-029, FR-030, SC-005, SC-011**</p>
 */
class OutboxObligationsPropertyTest {

    /** One generated run: a {@link FailureSaga} start (maybe faulted), an {@link AuditSaga} start or a send. */
    sealed interface Run permits FailureRun, AuditRun, SendRun {
    }

    record FailureRun(Optional<FaultPoint> fault) implements Run {
    }

    record AuditRun(AddNote.Mode mode) implements Run {
    }

    record SendRun(AddNote.Mode mode) implements Run {
    }

    /** What a committed batch must look like. */
    private record Expected(String sagaType, List<Object> events) {
    }

    @Provide
    Arbitrary<Run> runs() {
        Arbitrary<Run> failure = Arbitraries.of(FaultPoint.class).optional().map(FailureRun::new);
        Arbitrary<Run> audit = Arbitraries.of(AddNote.Mode.class).map(AuditRun::new);
        Arbitrary<Run> send = Arbitraries.of(AddNote.Mode.class).map(SendRun::new);
        return Arbitraries.oneOf(failure, audit, send);
    }

    @Provide
    Arbitrary<List<Run>> sequences() {
        return runs().list().ofMinSize(1).ofMaxSize(6);
    }

    @Provide
    Arbitrary<Timing> timings() {
        return Arbitraries.of(Timing.class);
    }

    @Property(tries = 100)
    void eachCommittedUnitOfWorkHandsOffExactlyItsBufferedEvents(@ForAll("sequences") List<Run> sequence,
            @ForAll("timings") Timing timing) {
        World world = new World(timing);
        List<Expected> expected = new ArrayList<>();
        for (int index = 0; index < sequence.size(); index++) {
            world.run(sequence.get(index), "id-" + index).ifPresent(expected::add);
        }

        List<OutgoingMessages> committed = world.outbox.committed();
        assertThat(committed).extracting(batch -> new Expected(batch.sagaType(), batch.events()))
                .containsExactlyElementsOf(expected);
        assertThat(committed).extracting(OutgoingMessages::unitOfWorkId).doesNotHaveDuplicates();
        // 008: recordedEvents holds every handed-on event, in the same relative order.
        assertThat(committed).allSatisfy(batch ->
                assertThat(batch.recordedEvents()).containsSubsequence(batch.events()));
        assertThat(committed).allSatisfy(batch ->
                assertThat(batch.sagaId() == null).isEqualTo(batch.sagaType() == null));
    }

    /**
     * The one {@link SagaObjectProvider} of {@link World}: {@link FailureSaga} through {@link FailureSagaObjects}, the
     * fan-out sagas through their constructors. A registered provider must support every saga type (startup check).
     */
    private record AllSagaObjects(FailureSagaObjects failureSagas) implements SagaObjectProvider {

        @Override
        public boolean supports(Class<?> sagaType) {
            return failureSagas.supports(sagaType) || sagaType == AuditSaga.class || sagaType == ReviewSaga.class;
        }

        @Override
        public Object newInstance(Class<?> sagaType) {
            if (sagaType == AuditSaga.class) {
                return new AuditSaga();
            }
            if (sagaType == ReviewSaga.class) {
                return new ReviewSaga();
            }
            return failureSagas.newInstance(sagaType);
        }
    }

    /** One engine with every fixture registered and a capturing outbox of the given timing. */
    private static final class World {

        private final FaultPlan plan = new FaultPlan();
        private final SlotRepository slots = new SlotRepository(plan);
        private final LabelResolver labels = new LabelResolver(plan);
        private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        private final CapturingOutbox outbox;
        private final SagaRuntime engine;

        World(Timing timing) {
            FeeService fees = new FeeService(plan);
            NoteService notes = new NoteService();
            boundary.participant(fees).participant(slots).participant(labels).participant(notes);
            outbox = timing == Timing.IN_TRANSACTION
                    ? CapturingOutbox.inTransaction(boundary)
                    : CapturingOutbox.afterCommit();
            MapHandlerInstanceProvider instances = new MapHandlerInstanceProvider()
                    .register(fees).register(slots).register(notes);
            engine = SagaRuntime.builder()
                    .register(FailureSaga.class, FeeService.class, Slot.class, SlotRepository.class, Label.class,
                            Parcel.class, Fee.class, AuditSaga.class, ReviewSaga.class, NoteService.class, Case.class)
                    .storage(new TransactionalInMemoryStorageProvider(boundary))
                    .contextStore(new InMemorySagaContextStore())
                    .transactions(boundary)
                    .dispatcher(new RecordingEventDispatcher())
                    .outbox(outbox)
                    .handlerInstances(new FailingHandlerInstances(plan, instances))
                    .aggregateResolver(labels)
                    .parameterResolver(new FeeScheduleResolver(plan, new FeeSchedule(150)))
                    .sagaObjects(new AllSagaObjects(new FailureSagaObjects(plan)))
                    .build();
            engine.start();
        }

        /** Runs one generated run under a fresh id; returns the batch it must leave, if it must leave one. */
        Optional<Expected> run(Run run, String id) {
            return switch (run) {
                case FailureRun failure -> {
                    slots.add(Slot.slotIdFor(id));
                    labels.add(Label.labelIdFor(id));
                    failure.fault().ifPresent(plan::arm);
                    engine.receive(new ParcelRequested(id, 4));
                    plan.reset();
                    yield failure.fault().isPresent() ? Optional.empty()
                            : Optional.of(new Expected(FailureSaga.class.getName(),
                                    List.of(new LabelPrinted(Label.labelIdFor(id), id))));
                }
                case AuditRun audit -> {
                    engine.receive(new CaseOpened(id, audit.mode()));
                    yield Optional.of(new Expected(AuditSaga.class.getName(), auditBuffered(audit.mode(), id)));
                }
                case SendRun send -> {
                    engine.commandGateway().send(new AddNote(id, send.mode()));
                    yield Optional.of(new Expected(null, recorded(send.mode(), id)));
                }
            };
        }

        /** What {@code NoteService} records for {@code mode}. */
        private static List<Object> recorded(AddNote.Mode mode, String caseId) {
            return switch (mode) {
                case NOTE -> List.of(new CaseNoted(caseId));
                case CLOSE_THEN_ESCALATE -> List.of(new CaseClosed(caseId), new CaseEscalated(caseId));
            };
        }

        /** What an {@code AuditSaga} unit of work leaves unrun: everything but the {@code CaseClosed} that ends it. */
        private static List<Object> auditBuffered(AddNote.Mode mode, String caseId) {
            return switch (mode) {
                case NOTE -> List.of(new CaseNoted(caseId));
                case CLOSE_THEN_ESCALATE -> List.of(new CaseEscalated(caseId));
            };
        }
    }
}
