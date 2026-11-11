package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/** Rule R23 (specs/007-in-memory-transport FR-010, contracts/core-spi.md): a passed-in OUTBOX command has a handler. */
class OutboxCommandValidationTest {

    @Command(delivery = CommandDelivery.OUTBOX)
    record ChargeCard(String invoiceId) {
    }

    @Command(delivery = CommandDelivery.OUTBOX)
    record AnnulInvoice(String invoiceId) {
    }

    @Command
    record PlainCommand(String id) {
    }

    static final class CardService {
        @CommandHandler
        public void handle(ChargeCard command) {
        }
    }

    private final AnnotationScanner scanner = new ReflectiveAnnotationScanner();

    @Test
    @DisplayName("a passed-in OUTBOX command with a handler among the passed-in hosts is valid")
    void handledOutboxCommand() {
        ValidationReport report = scanner.validateAll(List.of(ChargeCard.class, CardService.class));

        assertThat(report.modelErrors()).isEmpty();
    }

    @Test
    @DisplayName("a passed-in OUTBOX command without a handler is a model error, in simple-name order")
    void unhandledOutboxCommands() {
        ValidationReport report = scanner.validateAll(List.of(ChargeCard.class, AnnulInvoice.class));

        assertThat(report.modelErrors()).containsExactly(
                "Command AnnulInvoice is delivered through the outbox but has no @CommandHandler",
                "Command ChargeCard is delivered through the outbox but has no @CommandHandler");
    }

    @Test
    @DisplayName("an IN_UNIT_OF_WORK command without a handler is not R23's concern")
    void plainCommandIgnored() {
        assertThat(scanner.validateAll(List.of(PlainCommand.class)).modelErrors()).isEmpty();
    }
}
