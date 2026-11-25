package io.github.gkosharovdev.sagaforge.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineDurationException;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.deadline.OpenDeadline;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentWindowSaga;
import io.github.gkosharovdev.sagaforge.runtime.internal.EngineSagaLifecycle.PendingWrite;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link DeadlineModel}, {@link DeadlinePlan} and the lifecycle's deadline bookkeeping (specs/017-saga-deadlines
 * contracts/runtime-deadlines.md "Starting, replacing, cancelling"; tasks.md T020, T021, T032).
 */
class DeadlinePlanTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00.123456Z");
    private static final String SAGA = "saga-1";

    private final InMemoryStorageProvider storage = new InMemoryStorageProvider();
    private final InMemorySagaContextStore contexts = new InMemorySagaContextStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TimeAndIdentity sources = new TimeAndIdentity(clock, () -> SAGA, () -> "uow");
    private final SagaModel saga = SagaModel.of(PaymentWindowSaga.class);

    private EngineSagaContext context() {
        return new EngineSagaContext(SAGA, contexts, clock);
    }

    private DeadlinePlan plan(String unitOfWorkId) {
        return new DeadlinePlan(saga, SAGA, unitOfWorkId, clock::instant, this::context);
    }

    /** A lifecycle holding a RUNNING instance with {@code open}. */
    private EngineSagaLifecycle running(Map<String, OpenDeadline> open) {
        EngineSagaLifecycle created = new EngineSagaLifecycle(saga, storage, sources);
        SagaInstance started = created.start(PaymentWindowSaga.class, "orderId", "o-1");
        created.advance(SAGA);
        SagaInstance instance = created.current().withOpenDeadlines(open);
        EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(saga, storage, sources);
        lifecycle.load(instance);
        assertThat(started.sagaId()).isEqualTo(SAGA);
        return lifecycle;
    }

    private static OpenDeadline open(String name, String unitOfWork) {
        return new OpenDeadline(SAGA + ":deadline:" + name + ":" + unitOfWork, NOW.plusSeconds(60),
                PaymentWindowSaga.RESERVED, NOW);
    }

    @Nested
    class Model {

        @Test
        @DisplayName("declarations by step, the timeout step by name, the cancelling steps; fixed or property durations")
        void reads() {
            DeadlineModel model = DeadlineModel.of(PaymentWindowSaga.class,
                    key -> key.equals("deadline.reminder") ? Optional.of("PT3M") : Optional.empty());

            assertThat(model.startedBy(PaymentWindowSaga.RESERVED))
                    .extracting(DeadlineModel.Declaration::name)
                    .containsExactly(PaymentWindowSaga.WINDOW, PaymentWindowSaga.REMINDER);
            assertThat(model.startedBy(PaymentWindowSaga.RESERVED).get(0).durationFrom()).isEqualTo("paymentWindow");
            assertThat(model.startedBy(PaymentWindowSaga.REMIND).get(0).fixed()).contains(Duration.ofMinutes(3));
            assertThat(model.cancelledBy(PaymentWindowSaga.DECLINED)).containsExactly(PaymentWindowSaga.WINDOW);
            assertThat(model.expiryStep(PaymentWindowSaga.WINDOW)).contains(PaymentWindowSaga.EXPIRED);
            assertThat(model.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("an unset ${key} falls back to its default; one without default is a startup error")
        void placeholders() {
            assertThat(DeadlineModel.of(PaymentWindowSaga.class, key -> Optional.empty())
                    .startedBy(PaymentWindowSaga.REMIND).get(0).fixed()).contains(Duration.ofMinutes(10));
            assertThat(DeadlineModel.placeholderErrors(PaymentWindowSaga.class, key -> Optional.of("-PT1M")))
                    .hasSize(2).allSatisfy(error -> assertThat(error).endsWith("which is negative"));
        }
    }

    @Nested
    class Starting {

        @Test
        @DisplayName("a completed step opens its deadlines: id, due time truncated to ms, one scheduled message each")
        void opens() {
            context().putState("paymentWindow", "PT30M");
            EngineSagaLifecycle lifecycle = running(Map.of());

            plan("uow-7").stepCompleted(PaymentWindowSaga.RESERVED, lifecycle);

            PendingWrite write = lifecycle.drain(SAGA);
            OpenDeadline window = write.instance().openDeadlines().get(PaymentWindowSaga.WINDOW);
            assertThat(window.deadlineId()).isEqualTo("saga-1:deadline:paymentWindow:uow-7");
            assertThat(window.dueAt()).isEqualTo(Instant.parse("2026-01-01T00:30:00.123Z"));
            assertThat(window.startedAt()).isEqualTo(NOW);
            assertThat(write.scheduled()).extracting(ScheduledMessage::messageId)
                    .containsExactly("saga-1:deadline:paymentWindow:uow-7", "saga-1:deadline:reminder:uow-7");
            DeadlineReached payload = (DeadlineReached) write.scheduled().get(0).payload();
            assertThat(payload.dueAt()).isEqualTo(window.dueAt());
            assertThat(payload.startedByStep()).isEqualTo(PaymentWindowSaga.RESERVED);
            assertThat(write.cancelled()).isEmpty();
        }

        @Test
        @DisplayName("starting a deadline already open replaces it and cancels the old id")
        void replaces() {
            context().putState("paymentWindow", "PT30M");
            EngineSagaLifecycle lifecycle = running(Map.of(PaymentWindowSaga.WINDOW,
                    open(PaymentWindowSaga.WINDOW, "uow-1")));

            plan("uow-2").stepCompleted(PaymentWindowSaga.RESERVED, lifecycle);

            PendingWrite write = lifecycle.drain(SAGA);
            assertThat(write.cancelled()).containsExactly(Map.entry("saga-1:deadline:paymentWindow:uow-1",
                    PaymentWindowSaga.WINDOW));
            assertThat(write.instance().openDeadlines().get(PaymentWindowSaga.WINDOW).deadlineId())
                    .isEqualTo("saga-1:deadline:paymentWindow:uow-2");
        }

        @Test
        @DisplayName("a missing durationFrom attribute is a DeadlineDurationException naming saga, deadline, attribute")
        void missingContextValue() {
            EngineSagaLifecycle lifecycle = running(Map.of());

            assertThatThrownBy(() -> plan("uow-1").stepCompleted(PaymentWindowSaga.RESERVED, lifecycle))
                    .isInstanceOf(DeadlineDurationException.class)
                    .hasMessageContaining("PaymentWindowSaga: deadline 'paymentWindow'")
                    .hasMessageContaining("SagaContext attribute 'paymentWindow', which holds no value");
        }
    }

    @Nested
    class Closing {

        @Test
        @DisplayName("a cancelledBy step cancels that deadline only")
        void cancelledBy() {
            EngineSagaLifecycle lifecycle = running(Map.of(PaymentWindowSaga.WINDOW,
                    open(PaymentWindowSaga.WINDOW, "uow-1"), PaymentWindowSaga.REMINDER,
                    open(PaymentWindowSaga.REMINDER, "uow-1")));

            plan("uow-2").stepCompleted(PaymentWindowSaga.DECLINED, lifecycle);

            PendingWrite write = lifecycle.drain(SAGA);
            assertThat(write.cancelled()).containsOnlyKeys("saga-1:deadline:paymentWindow:uow-1");
            assertThat(write.instance().openDeadlines()).containsOnlyKeys(PaymentWindowSaga.REMINDER);
        }

        @Test
        @DisplayName("the timeout step's own deadline is expired, not cancelled")
        void expires() {
            EngineSagaLifecycle lifecycle = running(Map.of(PaymentWindowSaga.WINDOW,
                    open(PaymentWindowSaga.WINDOW, "uow-1")));

            plan("uow-2").expire(PaymentWindowSaga.WINDOW, lifecycle);

            PendingWrite write = lifecycle.drain(SAGA);
            assertThat(write.cancelled()).isEmpty();
            assertThat(write.instance().openDeadlines()).isEmpty();
        }

        @Test
        @DisplayName("a deadline opened and closed in one write is never scheduled and never cancelled")
        void openedAndClosedInOneWrite() {
            context().putState("paymentWindow", "PT30M");
            EngineSagaLifecycle lifecycle = running(Map.of());
            plan("uow-1").stepCompleted(PaymentWindowSaga.RESERVED, lifecycle);

            lifecycle.complete(SAGA, PaymentWindowSaga.AUTHORIZED);

            PendingWrite write = lifecycle.drain(SAGA);
            assertThat(write.scheduled()).isEmpty();
            assertThat(write.cancelled()).isEmpty();
            assertThat(write.instance().openDeadlines()).isEmpty();
        }

        private void leavingRunningCancelsAll(Consumer<EngineSagaLifecycle> transition) {
            EngineSagaLifecycle lifecycle = running(Map.of(PaymentWindowSaga.WINDOW,
                    open(PaymentWindowSaga.WINDOW, "uow-1"), PaymentWindowSaga.REMINDER,
                    open(PaymentWindowSaga.REMINDER, "uow-1")));

            transition.accept(lifecycle);

            PendingWrite write = lifecycle.drain(SAGA);
            assertThat(write.cancelled()).containsOnlyKeys("saga-1:deadline:paymentWindow:uow-1",
                    "saga-1:deadline:reminder:uow-1");
            assertThat(write.instance().openDeadlines()).isEmpty();
        }

        @Test
        @DisplayName("Property 41: completing cancels every open deadline")
        void complete() {
            leavingRunningCancelsAll(lifecycle -> lifecycle.complete(SAGA, PaymentWindowSaga.AUTHORIZED));
        }

        @Test
        @DisplayName("Property 41: the FAILED write cancels every open deadline")
        void fail() {
            leavingRunningCancelsAll(lifecycle -> lifecycle.fail(SAGA, new SagaFailure(PaymentWindowSaga.DECLINED,
                    "java.lang.IllegalStateException", "boom", NOW)));
        }

        @Test
        @DisplayName("Property 41: starting compensation (business, FINAL, command failure, deadline) cancels all")
        void startCompensation() {
            List<CompensationTrigger> triggers = List.of(
                    CompensationTrigger.failureOutcome(PaymentWindowSaga.DECLINED, "x.Event", NOW),
                    CompensationTrigger.failed(new SagaFailure(PaymentWindowSaga.DECLINED,
                            "java.lang.IllegalStateException", "boom", NOW,
                            io.github.gkosharovdev.sagaforge.core.failure.FailureClassification.FINAL, 1), NOW),
                    CompensationTrigger.deadlineExpired(PaymentWindowSaga.EXPIRED, DeadlineReached.class.getName(),
                            PaymentWindowSaga.REMINDER, NOW));
            triggers.forEach(trigger -> leavingRunningCancelsAll(
                    lifecycle -> lifecycle.startCompensation(SAGA, trigger)));
        }
    }
}
