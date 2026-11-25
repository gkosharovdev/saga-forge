package io.github.gkosharovdev.sagaforge.core.deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.ScheduledCommandId;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.outcome.OutcomeTrigger;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.ScheduledMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The core vocabulary of specs/017-saga-deadlines data-model.md. */
class DeadlineVocabularyTest {

    private static final Instant AT = Instant.parse("2026-11-25T10:00:00Z");
    private static final Instant DUE = AT.plusSeconds(900);
    private static final DeadlineReached REACHED = new DeadlineReached("com.example.CheckoutSaga", "saga-1",
            "paymentWindow", "saga-1:deadline:paymentWindow:uow-1", DUE, "onStockReserved");

    @Nested
    class Values {

        @Test
        void deadlineReachedIsIdentifiedByItsDeadlineId() {
            assertThat(REACHED.eventId()).isEqualTo("saga-1:deadline:paymentWindow:uow-1");
        }

        @Test
        void deadlineReachedRejectsBlankComponents() {
            assertThatThrownBy(() -> new DeadlineReached("S", "saga-1", " ", "id", DUE, "step"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deadlineName");
            assertThatThrownBy(() -> new DeadlineReached("S", "saga-1", "d", "id", null, "step"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dueAt");
        }

        @Test
        void openDeadlineRejectsMissingComponents() {
            assertThat(new OpenDeadline("id", DUE, "step", AT).dueAt()).isEqualTo(DUE);
            assertThatThrownBy(() -> new OpenDeadline("", DUE, "step", AT))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deadlineId");
            assertThatThrownBy(() -> new OpenDeadline("id", DUE, "step", null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("startedAt");
        }

        @Test
        void durationFailureIsFinal() {
            assertThat(new DeadlineDurationException("bad")).isInstanceOf(FinalFailure.class);
            assertThat(FailureClassification.of(new DeadlineDurationException("bad")))
                    .isEqualTo(FailureClassification.FINAL);
        }

        @Test
        void scheduledCommandIdPrintsItsValue() {
            assertThat(new ScheduledCommandId("uow-1:scheduled:0")).hasToString("uow-1:scheduled:0");
            assertThatThrownBy(() -> new ScheduledCommandId(" ")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void gatewayDefaultsRefuseToSchedule() {
            CommandGateway gateway = command -> { };
            assertThatThrownBy(() -> gateway.schedule(new Object(), AT))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> gateway.cancel(new ScheduledCommandId("x")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Triggers {

        @Test
        void deadlineExpiredTriggerCarriesTheDeadlineAndNoFailure() {
            CompensationTrigger trigger = CompensationTrigger.deadlineExpired("onPaymentWindowExpired",
                    DeadlineReached.class.getName(), "paymentWindow", AT);
            assertThat(trigger.kind()).isEqualTo(CompensationTriggerKind.DEADLINE_EXPIRED);
            assertThat(trigger.failure()).isNull();
            OutcomeTrigger outcome = OutcomeTrigger.of(trigger);
            assertThat(outcome.deadlineName()).isEqualTo("paymentWindow");
            assertThat(outcome.classification()).isNull();
        }

        @Test
        void deadlineNameIsRequiredIffDeadlineExpired() {
            assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.DEADLINE_EXPIRED, "step",
                    "E", null, null, AT, null)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deadlineName");
            assertThatThrownBy(() -> new CompensationTrigger(CompensationTriggerKind.FAILURE_OUTCOME, "step",
                    "E", null, null, AT, "d")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deadlineName");
            assertThatThrownBy(() -> new OutcomeTrigger(CompensationTriggerKind.DEADLINE_EXPIRED, "step", null,
                    null, null, AT, null, null)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deadlineName");
            assertThatThrownBy(() -> new OutcomeTrigger(CompensationTriggerKind.FAILURE_OUTCOME, "step", null,
                    null, null, AT, null, "d")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deadlineName");
        }
    }

    @Nested
    class Instances {

        private SagaInstance running(Map<String, OpenDeadline> deadlines) {
            return new SagaInstance("saga-1", "S", Map.of(), Map.of(), SagaStatus.RUNNING, AT, AT, Map.of(), null,
                    null, null, null, deadlines);
        }

        @Test
        void keepsOpenDeadlinesAcrossCopies() {
            SagaInstance instance = running(Map.of("paymentWindow", new OpenDeadline("id", DUE, "step", AT)));
            assertThat(instance.withPendingRetry(null).openDeadlines()).containsKey("paymentWindow");
            assertThat(instance.withOpenDeadlines(Map.of()).openDeadlines()).isEmpty();
        }

        @Test
        void openDeadlinesOnlyWhileActive() {
            assertThatThrownBy(() -> new SagaInstance("saga-1", "S", Map.of(), Map.of(), SagaStatus.COMPLETED, AT,
                    AT, Map.of(), "end", null, null, null,
                    Map.of("d", new OpenDeadline("id", DUE, "step", AT))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("openDeadlines");
        }

        @Test
        void olderConstructorsHaveNoOpenDeadlines() {
            assertThat(new SagaInstance("saga-1", "S", Map.of(), Map.of(), SagaStatus.RUNNING, AT, AT, Map.of(),
                    null, null).openDeadlines()).isEmpty();
        }
    }

    @Nested
    class Messages {

        @Test
        void scheduledMessageForADeadlineIsDueAtTheDeadline() {
            ScheduledMessage message = ScheduledMessage.deadline(REACHED);
            assertThat(message.messageId()).isEqualTo(REACHED.deadlineId());
            assertThat(message.notBefore()).isEqualTo(DUE);
            assertThat(message.kind()).isEqualTo(MessageKind.EVENT);
        }

        @Test
        void scheduledMessageRules() {
            assertThatThrownBy(() -> new ScheduledMessage("m", MessageKind.EVENT, "not a deadline", null, DUE))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DeadlineReached");
            assertThatThrownBy(() -> new ScheduledMessage("m", MessageKind.COMMAND, new Object(), null, DUE))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("commandId");
        }

        @Test
        void batchMayHoldOnlyScheduledMessages() {
            OutgoingMessages batch = new OutgoingMessages("uow-1", "S", "saga-1", List.of(), List.of(), List.of(),
                    List.of(ScheduledMessage.deadline(REACHED)));
            assertThat(batch.scheduled()).hasSize(1);
            assertThat(new OutgoingMessages("uow-1", "S", "saga-1", List.of("e")).scheduled()).isEmpty();
        }

        @Test
        void aScheduledNewMessageHasNoOrderingKeyAndStartsAtItsDueTime() {
            assertThatThrownBy(() -> new NewMessage("m", MessageKind.EVENT, "T", REACHED, null, "key", null, null,
                    AT, Map.of(), DUE)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("orderingKey");
            NewMessage entry = new NewMessage("m", MessageKind.EVENT, "T", REACHED, null, null, null, null, AT,
                    Map.of(), DUE);
            StoredMessage stored = StoredMessage.appended(1, entry);
            assertThat(stored.nextAttemptAt()).isEqualTo(DUE);
            assertThat(stored.cancelled(AT).status()).isEqualTo(MessageStatus.CANCELLED);
            assertThat(StoredMessage.appended(2, new NewMessage("n", MessageKind.EVENT, "T", "e", null, null, null,
                    null, AT)).nextAttemptAt()).isEqualTo(AT);
        }

        @Test
        void spiDefaultsDoNotSchedule() {
            Outbox outbox = batch -> { };
            assertThat(outbox.supportsScheduling()).isFalse();
            assertThatThrownBy(() -> outbox.cancel(List.of("m"))).isInstanceOf(UnsupportedOperationException.class);
            MessageStore store = (MessageStore) java.lang.reflect.Proxy.newProxyInstance(
                    MessageStore.class.getClassLoader(), new Class<?>[] {MessageStore.class},
                    (proxy, method, args) -> java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args));
            assertThat(store.supportsScheduling()).isFalse();
            assertThatThrownBy(() -> store.cancel(null, List.of("m"), AT))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
