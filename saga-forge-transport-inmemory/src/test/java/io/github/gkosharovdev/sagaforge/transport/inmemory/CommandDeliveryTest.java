package io.github.gkosharovdev.sagaforge.transport.inmemory;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.log.LoggedMessage;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.log.MessageStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.BillingSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardCharged;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ChargeCard;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.InvoiceIssued;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageLog;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;

/** User Story 3 (spec.md, tasks.md T060): logged commands travel through the same log. */
class CommandDeliveryTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final TransportHarness harness = new TransportHarness(TransportHarness.FAST, BillingSaga.class,
            CardService.class, ChargeCard.class);

    @AfterEach
    void stop() {
        harness.close();
    }

    private LoggedMessage charge() {
        return harness.messagesOf(ChargeCard.class).get(0);
    }

    @Test
    @DisplayName("scenarios 1 and 4: a logged command is handled after the step commits; its events go through the log")
    void handledAfterCommit() {
        harness.start();
        harness.publish(new InvoiceIssued("i-1"));

        await(() -> harness.instance(BillingSaga.class, SagaStatus.COMPLETED).isPresent(), WAIT, "billing to end");
        harness.awaitQuiet(WAIT);
        assertThat(harness.cards.charges()).singleElement().satisfies(c -> {
            assertThat(c.invoiceId()).isEqualTo("i-1");
            assertThat(c.thread()).startsWith("saga-forge-transport-");
        });
        assertThat(charge().kind()).isEqualTo(MessageKind.COMMAND);
        assertThat(charge().sourceSagaType()).isEqualTo(BillingSaga.class.getName());
        assertThat(charge().orderingKey()).isEqualTo(ChargeCard.class.getName() + "#i-1");
        assertThat(harness.messagesOf(CardCharged.class)).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(MessageStatus.CONSUMED));
    }

    @Test
    @DisplayName("scenarios 2 and 3: stopped before handling, the command is handled once after restart, same id")
    void survivesRestartWithSameIdentity() {
        // The process runs the start step and stores the command, then stops before any delivery.
        harness.engine().receive(new InvoiceIssued("i-1"));
        assertThat(harness.messagesOf(ChargeCard.class)).singleElement()
                .satisfies(m -> assertThat(m.status()).isEqualTo(MessageStatus.UNCONSUMED));
        assertThat(harness.cards.charges()).isEmpty();
        CommandId stored = charge().commandId();

        harness.restart();

        await(() -> harness.instance(BillingSaga.class, SagaStatus.COMPLETED).isPresent(), WAIT, "billing to end");
        harness.awaitQuiet(WAIT);
        assertThat(harness.cards.charges()).extracting(CardService.Charge::commandId).containsExactly(stored);
        assertThat(stored.stepName()).isEqualTo(BillingSaga.START_STEP);
    }

    @Test
    @DisplayName("a failing handler is retried with the same identity, then consumed")
    void failingHandlerRetried() {
        harness.cards.failNext(2);
        harness.start();
        harness.publish(new InvoiceIssued("i-1"));

        await(() -> harness.instance(BillingSaga.class, SagaStatus.COMPLETED).isPresent(), WAIT, "billing to end");
        harness.awaitQuiet(WAIT);
        assertThat(harness.cards.charges()).hasSize(3);
        assertThat(harness.cards.charges()).extracting(CardService.Charge::commandId).containsOnly(
                charge().commandId());
        assertThat(charge().attempts()).isEqualTo(3);
        assertThat(harness.messagesOf(CardCharged.class)).hasSize(1);
    }

    @Test
    @DisplayName("a consumed mark failing inside the handler's transaction rolls the handler's effects back")
    void consumedMarkAtomicWithHandler() {
        harness.log.failNext(InMemoryMessageLog.Operation.MARK_CONSUMED, new IllegalStateException("mark failed"));
        harness.start();
        harness.publish(new InvoiceIssued("i-1"));

        await(() -> harness.instance(BillingSaga.class, SagaStatus.COMPLETED).isPresent(), WAIT, "billing to end");
        harness.awaitQuiet(WAIT);
        assertThat(harness.messagesOf(CardCharged.class)).hasSize(1);
    }
}
