package io.github.gkosharovdev.sagaforge.core.spi.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/** Validation of the {@code core.spi.log} records (specs/007-in-memory-transport data-model.md). */
class LogRecordsTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final CommandId COMMAND_ID = CommandId.of("s-1", "step", "com.example.Pay", 1);

    static LogEntry event(String id) {
        return new LogEntry(id, MessageKind.EVENT, "com.example.Paid", "paid", null, "Order#1", "Saga", "s-1", NOW);
    }

    @Nested
    @DisplayName("LogEntry")
    class LogEntries {

        @Test
        @DisplayName("an event and a command entry are accepted")
        void accepted() {
            assertThat(event("u:0").kind()).isEqualTo(MessageKind.EVENT);
            var command = new LogEntry(COMMAND_ID.value(), MessageKind.COMMAND, "com.example.Pay", "pay", COMMAND_ID,
                    null, null, null, NOW);
            assertThat(command.commandId()).isEqualTo(COMMAND_ID);
        }

        @Test
        @DisplayName("blank ids and types, null kind, payload or time are rejected")
        void requiredFields() {
            assertThatThrownBy(() -> new LogEntry(" ", MessageKind.EVENT, "T", "p", null, null, null, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LogEntry("id", null, "T", "p", null, null, null, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LogEntry("id", MessageKind.EVENT, "", "p", null, null, null, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LogEntry("id", MessageKind.EVENT, "T", null, null, null, null, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LogEntry("id", MessageKind.EVENT, "T", "p", null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a commandId is present if and only if the entry is a command")
        void commandIdIffCommand() {
            assertThatThrownBy(() -> new LogEntry("id", MessageKind.EVENT, "T", "p", COMMAND_ID, null, null, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LogEntry("id", MessageKind.COMMAND, "T", "p", null, null, null, null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("source saga type and id come together")
        void sourceTogether() {
            assertThatThrownBy(() -> new LogEntry("id", MessageKind.EVENT, "T", "p", null, null, "Saga", null, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LogEntry("id", MessageKind.EVENT, "T", "p", null, null, null, "s-1", NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("LoggedMessage")
    class LoggedMessages {

        @Test
        @DisplayName("appended is unconsumed, without attempts, due at its recording time")
        void appended() {
            var message = LoggedMessage.appended(7, event("u:0"));

            assertThat(message.position()).isEqualTo(7);
            assertThat(message.status()).isEqualTo(MessageStatus.UNCONSUMED);
            assertThat(message.attempts()).isZero();
            assertThat(message.nextAttemptAt()).isEqualTo(NOW);
            assertThat(message.hasPayload()).isTrue();
            assertThat(message.withoutPayload().hasPayload()).isFalse();
        }

        @Test
        @DisplayName("lease fields, consumedAt and setAsideReason follow the status rules")
        void invariants() {
            var base = LoggedMessage.appended(1, event("u:0"));
            assertThatThrownBy(() -> copy(base, MessageStatus.UNCONSUMED, 0, "c", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> copy(base, MessageStatus.CONSUMED, 1, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> copy(base, MessageStatus.UNCONSUMED, 1, null, null, NOW, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> copy(base, MessageStatus.SET_ASIDE, 1, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> copy(base, MessageStatus.UNCONSUMED, -1, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(copy(base, MessageStatus.CONSUMED, 1, null, null, NOW, null).consumedAt()).isEqualTo(NOW);
            assertThat(copy(base, MessageStatus.SET_ASIDE, 10, null, null, null, "MAX_ATTEMPTS").setAsideReason())
                    .isEqualTo("MAX_ATTEMPTS");
            assertThat(copy(base, MessageStatus.UNCONSUMED, 1, "c", NOW, null, null).leasedBy()).isEqualTo("c");
        }

        private LoggedMessage copy(LoggedMessage m, MessageStatus status, int attempts, String leasedBy,
                Instant leasedUntil, Instant consumedAt, String reason) {
            return new LoggedMessage(m.position(), m.messageId(), m.kind(), m.payloadType(), m.payload(),
                    m.commandId(), m.orderingKey(), m.sourceSagaType(), m.sourceSagaId(), m.recordedAt(), status,
                    attempts, m.nextAttemptAt(), null, leasedBy, leasedUntil, consumedAt, reason, null);
        }
    }

    @Nested
    @DisplayName("DeliveryFailure")
    class DeliveryFailures {

        @Test
        @DisplayName("of() keeps the class name and truncates long messages to 4,000 characters")
        void ofTruncates() {
            var failure = DeliveryFailure.of(new IllegalStateException("x".repeat(5_000)), NOW);

            assertThat(failure.exceptionType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(failure.message()).hasSize(DeliveryFailure.MAX_MESSAGE_LENGTH);
            assertThat(DeliveryFailure.of(new RuntimeException(), NOW).message()).isNull();
        }

        @Test
        @DisplayName("blank type, null time or an over-long message are rejected")
        void rules() {
            assertThatThrownBy(() -> new DeliveryFailure(" ", "m", NOW)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DeliveryFailure("T", "m", null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DeliveryFailure("T", "x".repeat(4_001), NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("MessageQuery")
    class Queries {

        @Test
        @DisplayName("the default query matches everything with limit 100 and no payload")
        void defaults() {
            var query = MessageQuery.builder().build();

            assertThat(query.limit()).isEqualTo(MessageQuery.DEFAULT_LIMIT);
            assertThat(query.includePayload()).isFalse();
            assertThat(query.matches(LoggedMessage.appended(1, event("u:0")))).isTrue();
        }

        @Test
        @DisplayName("criteria combine with and")
        void criteria() {
            var message = LoggedMessage.appended(1, event("u:0"));

            assertThat(MessageQuery.builder().statuses(MessageStatus.CONSUMED).build().matches(message)).isFalse();
            assertThat(MessageQuery.builder().statuses(MessageStatus.UNCONSUMED).sourceSaga("Saga", "s-1")
                    .orderingKey("Order#1").payloadType("com.example.Paid").build().matches(message)).isTrue();
            assertThat(MessageQuery.builder().sourceSaga("Saga", "s-2").build().matches(message)).isFalse();
        }

        @Test
        @DisplayName("limit outside 1 to 1,000 or half a source saga is rejected")
        void rules() {
            assertThatThrownBy(() -> MessageQuery.builder().limit(0).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MessageQuery.builder().limit(1_001).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MessageQuery.builder().sourceSaga("Saga", null).build())
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(new MessageQuery(Set.of(), null, null, null, null, 1, false).statuses()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ConsumerHeartbeat and LeasedMessages")
    class Others {

        @Test
        @DisplayName("a heartbeat cannot be last seen before it started")
        void heartbeat() {
            assertThatThrownBy(() -> new ConsumerHeartbeat("c", "h", NOW, NOW.minusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(new ConsumerHeartbeat("c", "h", NOW, NOW).consumerId()).isEqualTo("c");
        }

        @Test
        @DisplayName("LeasedMessages counts readable and unreadable leases")
        void leased() {
            var message = LoggedMessage.appended(1, event("u:0"));
            var unreadable = new UnreadableMessageException(message, new ClassNotFoundException("gone"));
            var leased = new LeasedMessages(List.of(message), List.of(unreadable));

            assertThat(leased.size()).isEqualTo(2);
            assertThat(leased.isEmpty()).isFalse();
            assertThat(unreadable.leased().hasPayload()).isFalse();
            assertThat(new LeasedMessages(List.of(), List.of()).isEmpty()).isTrue();
        }
    }
}
