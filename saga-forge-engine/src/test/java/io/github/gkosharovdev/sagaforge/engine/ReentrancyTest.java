package io.github.gkosharovdev.sagaforge.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandoff;
import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.HandedOffEvents;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code receive} called back from inside a running unit of work is rejected and fails that unit of work; called from
 * an {@code AFTER_COMMIT} handoff it runs as a new unit of work (specs/004-saga-engine tasks.md T091, FR-007,
 * quickstart.md scenario 19).
 */
class ReentrancyTest {

    /** Where the callback into the engine comes from. */
    enum Origin { NONE, STEP, STEP_CAUGHT, HANDLER }

    @Aggregate
    public static final class Room {

        @AggregateIdentifier
        private String roomId;
    }

    /** Start event of {@link KnockSaga}; {@code origin} says where the saga calls back into the engine. */
    @DomainEvent(aggregate = Room.class)
    public record Knock(@AggregateIdentifier String roomId, Origin origin) {
    }

    /** Recorded by {@link EchoService}; ends {@link KnockSaga} in-process. */
    @DomainEvent(aggregate = Room.class)
    public record Echoed(@AggregateIdentifier String roomId) {
    }

    /** Recorded by {@link EchoService}; no step reacts to it, so every unit of work hands it off. */
    @DomainEvent(aggregate = Room.class)
    public record Heard(@AggregateIdentifier String roomId) {
    }

    /** Sent by {@link KnockSaga}'s start step. */
    @Command
    public record Echo(String roomId, Origin origin) {
    }

    /** Calls back into the engine from inside its handler when asked to. */
    public static final class EchoService {

        private final AtomicReference<EventReceiver> receiver;

        EchoService(AtomicReference<EventReceiver> receiver) {
            this.receiver = receiver;
        }

        @CommandHandler
        public void handle(Echo command) {
            if (command.origin() == Origin.HANDLER) {
                receiver.get().receive(new Knock("nested-" + command.roomId(), Origin.NONE));
            }
            DomainEvents.record(new Heard(command.roomId()));
            DomainEvents.record(new Echoed(command.roomId()));
        }
    }

    @Saga
    public static final class KnockSaga {

        @SagaId
        private String sagaId;

        private CommandGateway commandGateway;

        private final AtomicReference<EventReceiver> receiver;

        KnockSaga(AtomicReference<EventReceiver> receiver) {
            this.receiver = receiver;
        }

        @StartSaga
        @SagaStep(associationProperty = "roomId")
        public void onKnock(Knock event) {
            Knock nested = new Knock("nested-" + event.roomId(), Origin.NONE);
            switch (event.origin()) {
                case STEP -> receiver.get().receive(nested);
                case STEP_CAUGHT -> {
                    try {
                        receiver.get().receive(nested);
                    } catch (IllegalStateException rejected) {
                        // Swallowed on purpose: the engine must fail the unit of work anyway.
                    }
                }
                default -> {
                    // No callback from the step.
                }
            }
            commandGateway.send(new Echo(event.roomId(), event.origin()));
        }

        @EndSaga
        @SagaStep(associationProperty = "roomId")
        public void onEchoed(Echoed event) {
            // Outcome step.
        }
    }

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final AtomicReference<EventReceiver> receiver = new AtomicReference<>();

    private SagaEngine engine(EventHandoff handoff) {
        SagaObjectProvider sagaObjects = new SagaObjectProvider() {
            @Override
            public boolean supports(Class<?> sagaType) {
                return sagaType == KnockSaga.class;
            }

            @Override
            public Object newInstance(Class<?> sagaType) {
                return new KnockSaga(receiver);
            }
        };
        SagaEngine engine = SagaEngine.builder()
                .register(KnockSaga.class, EchoService.class, Room.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .handlerInstances(new MapHandlerInstanceProvider().register(new EchoService(receiver)))
                .sagaObjects(sagaObjects)
                .handoff(handoff)
                .build();
        engine.start();
        receiver.set(engine);
        return engine;
    }

    private SagaEngine engine() {
        return engine(new DispatchingEventHandoff(new RecordingEventDispatcher()));
    }

    private static SagaHandlingResult onlyResult(HandlingReport report) {
        assertThat(report.results()).hasSize(1);
        return report.results().get(0);
    }

    private void assertRejectedAndFailed(SagaHandlingResult result) {
        assertThat(result.outcome()).isEqualTo(HandlingOutcome.FAILED);
        assertThat(result.failure().causeType()).isEqualTo(IllegalStateException.class.getName());
        assertThat(result.failure().message()).contains("unit of work is running");
        assertThat(storage.committed().all()).singleElement()
                .satisfies(instance -> assertThat(instance.status()).isEqualTo(SagaStatus.FAILED));
    }

    @Test
    @DisplayName("receive from inside a step is rejected and fails that unit of work")
    void fromStep() {
        assertRejectedAndFailed(onlyResult(engine().receive(new Knock("room-1", Origin.STEP))));
    }

    @Test
    @DisplayName("receive from inside a step that catches the rejection still fails that unit of work")
    void fromStepCaught() {
        assertRejectedAndFailed(onlyResult(engine().receive(new Knock("room-1", Origin.STEP_CAUGHT))));
    }

    @Test
    @DisplayName("receive from inside a handler is rejected and fails that unit of work")
    void fromHandler() {
        assertRejectedAndFailed(onlyResult(engine().receive(new Knock("room-1", Origin.HANDLER))));
    }

    @Test
    @DisplayName("receive from inside an IN_TRANSACTION handoff is rejected and fails that unit of work")
    void fromInTransactionHandoff() {
        EventHandoff callingBack = new EventHandoff() {
            @Override
            public Timing timing() {
                return Timing.IN_TRANSACTION;
            }

            @Override
            public void handOff(HandedOffEvents batch) {
                receiver.get().receive(new Knock("nested", Origin.NONE));
            }
        };

        SagaHandlingResult result = onlyResult(engine(callingBack).receive(new Knock("room-1", Origin.NONE)));

        assertRejectedAndFailed(result);
        assertThat(result.failure().stepName()).isEqualTo("onEchoed");
    }

    @Test
    @DisplayName("receive from inside an AFTER_COMMIT handoff runs as a new unit of work after the first committed")
    void fromAfterCommitHandoff() {
        List<SagaStatus> committedAtCallback = new ArrayList<>();
        List<HandlingReport> nested = new ArrayList<>();
        AtomicBoolean calledBack = new AtomicBoolean();
        EventHandoff callingBackOnce = batch -> {
            // Set before the nested receive: its own unit of work hands off a batch too, which must not call back.
            if (calledBack.compareAndSet(false, true)) {
                storage.committed().all().forEach(instance -> committedAtCallback.add(instance.status()));
                nested.add(receiver.get().receive(new Knock("room-2", Origin.NONE)));
            }
        };

        SagaHandlingResult first = onlyResult(engine(callingBackOnce).receive(new Knock("room-1", Origin.NONE)));

        assertThat(first.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
        assertThat(committedAtCallback).containsExactly(SagaStatus.COMPLETED);
        assertThat(nested).singleElement().satisfies(report -> {
            SagaHandlingResult second = onlyResult(report);
            assertThat(second.outcome()).isEqualTo(HandlingOutcome.COMPLETED);
            assertThat(second.started()).isTrue();
            assertThat(second.sagaId()).isNotEqualTo(first.sagaId());
        });
        assertThat(storage.committed().all()).hasSize(2)
                .allSatisfy(instance -> assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED));
    }
}
