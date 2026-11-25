package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.failure.TransientFailure;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.RecordingCommandReceiver;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportUnderTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The relay's classification of a failed outbox command ({@code specs/012-outbox-command-failure-feedback}
 * contracts/relay-classification.md, tasks.md T014, T023, T033, T034), over a receiver that stands in for the runtime.
 */
class CommandFailureFeedbackTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 3;
    private static final TransportSettings SETTINGS = TransportSettings.defaults().withWorkers(2)
            .withMaxAttempts(MAX_ATTEMPTS).withBackoff(Duration.ofMillis(1), Duration.ofMillis(4))
            .withPollInterval(Duration.ofMillis(20));
    private static final CommandId SAGA_SCOPED = CommandId.of("saga-1", "pay", Refund.class.getName(), 1);
    private static final CommandId UNSCOPED = CommandId.unscoped(Refund.class.getName());

    record Refund(String paymentId) {
    }

    static final class Declined extends RuntimeException implements FinalFailure {
        Declined() {
            super("card declined");
        }
    }

    static final class Unavailable extends RuntimeException implements TransientFailure {
        Unavailable() {
            super("provider unavailable");
        }
    }

    private TransportUnderTest transport;
    private RecordingCommandReceiver commands;

    @AfterEach
    void stop() {
        transport.close();
    }

    private void startWith(Consumer<Object> handler) {
        transport = new TransportUnderTest(SETTINGS, event -> new HandlingReport(event, List.of()));
        commands = new RecordingCommandReceiver(transport.boundary, handler);
        transport.start(commands);
    }

    private StoredMessage message(String messageId) {
        return transport.log.all().stream().filter(m -> m.messageId().equals(messageId)).findFirst().orElseThrow();
    }

    private StoredMessage awaitSetAside(String messageId) {
        await(() -> message(messageId).status() == MessageStatus.SET_ASIDE, WAIT, "the command to be set aside");
        return message(messageId);
    }

    @Test
    @DisplayName("FINAL: one attempt, reported FINAL, set aside FINAL_FAILURE inside the reaction")
    void finalFailureReportedAtOnce() {
        startWith(command -> {
            throw new Declined();
        });

        String id = transport.sendCommand(new Refund("p-1"), SAGA_SCOPED);

        StoredMessage setAside = awaitSetAside(id);
        assertThat(commands.deliveries()).hasSize(1);
        assertThat(commands.reports()).singleElement().satisfies(report -> {
            assertThat(report.commandId()).isEqualTo(SAGA_SCOPED);
            assertThat(report.classification()).isEqualTo(FailureClassification.FINAL);
            assertThat(report.causeType()).isEqualTo(Declined.class.getName());
            assertThat(report.attempts()).isEqualTo(1);
        });
        assertThat(setAside.setAsideReason()).isEqualTo("FINAL_FAILURE");
        assertThat(setAside.lastFailure().exceptionType()).isEqualTo(Declined.class.getName());
        await(() -> !transport.logger.byEvent("saga.transport.setAside").isEmpty(), WAIT, "the set-aside warning");
    }

    @Test
    @DisplayName("unclassified: retried up to maxAttempts, then reported EXHAUSTED and set aside MAX_ATTEMPTS")
    void unclassifiedExhausted() {
        startWith(command -> {
            throw new IllegalStateException("boom");
        });

        String id = transport.sendCommand(new Refund("p-1"), SAGA_SCOPED);

        StoredMessage setAside = awaitSetAside(id);
        assertThat(commands.deliveries()).hasSize(MAX_ATTEMPTS);
        assertThat(commands.reports()).singleElement().satisfies(report -> {
            assertThat(report.classification()).isEqualTo(FailureClassification.EXHAUSTED);
            assertThat(report.causeType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(report.attempts()).isEqualTo(MAX_ATTEMPTS);
        });
        assertThat(setAside.setAsideReason()).isEqualTo("MAX_ATTEMPTS");
        assertThat(setAside.attempts()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("TransientFailure: retried with the relay's back-off and consumed once it succeeds, no report")
    void transientRetried() {
        AtomicInteger calls = new AtomicInteger();
        startWith(command -> {
            if (calls.incrementAndGet() < MAX_ATTEMPTS) {
                throw new Unavailable();
            }
        });

        String id = transport.sendCommand(new Refund("p-1"), SAGA_SCOPED);

        await(() -> message(id).status() == MessageStatus.CONSUMED, WAIT, "the command to be consumed");
        assertThat(commands.deliveries()).hasSize(MAX_ATTEMPTS);
        assertThat(commands.reports()).isEmpty();
    }

    @Test
    @DisplayName("a command sent outside a saga is set aside as before, with no report")
    void unscopedNotReported() {
        startWith(command -> {
            throw ((Refund) command).paymentId().equals("final") ? new Declined() : new IllegalStateException("boom");
        });

        String finalId = transport.sendCommand(new Refund("final"), UNSCOPED);
        String exhaustedId = transport.sendCommand(new Refund("exhausted"), UNSCOPED);

        assertThat(awaitSetAside(finalId).setAsideReason()).isEqualTo("FINAL_FAILURE");
        assertThat(awaitSetAside(exhaustedId).setAsideReason()).isEqualTo("MAX_ATTEMPTS");
        assertThat(message(finalId).attempts()).isEqualTo(1);
        assertThat(message(exhaustedId).attempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(commands.reports()).isEmpty();
    }

    @Test
    @DisplayName("C4: a report that throws leaves the command unconsumed; it is redelivered and reported once")
    void throwingReportRedelivers() {
        startWith(command -> {
            throw new Declined();
        });
        commands.failReports(1);

        String id = transport.sendCommand(new Refund("p-1"), SAGA_SCOPED);

        StoredMessage setAside = awaitSetAside(id);
        assertThat(commands.deliveries()).hasSize(2);
        assertThat(commands.reports()).singleElement()
                .satisfies(report -> assertThat(report.attempts()).isEqualTo(2));
        assertThat(setAside.setAsideReason()).isEqualTo("FINAL_FAILURE");
        assertThat(transport.logger.byEvent("saga.transport.reportFailed")).hasSize(1);
    }

    @Test
    @DisplayName("an unreadable saga-scoped command is reported FINAL without a delivery")
    void unreadableReported() {
        transport = new TransportUnderTest(SETTINGS, event -> new HandlingReport(event, List.of()));
        commands = new RecordingCommandReceiver(transport.boundary, command -> { });
        String id = transport.sendCommand(new Refund("p-1"), SAGA_SCOPED);
        transport.log.markUnreadable(id);

        transport.start(commands);

        StoredMessage setAside = awaitSetAside(id);
        assertThat(commands.deliveries()).isEmpty();
        assertThat(commands.reports()).singleElement()
                .satisfies(report -> assertThat(report.classification()).isEqualTo(FailureClassification.FINAL));
        assertThat(setAside.setAsideReason()).startsWith("UNREADABLE");
    }
}
