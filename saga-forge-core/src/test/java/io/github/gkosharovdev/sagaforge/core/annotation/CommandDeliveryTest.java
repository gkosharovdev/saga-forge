package io.github.gkosharovdev.sagaforge.core.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code @Command.delivery} (specs/007-in-memory-transport FR-010). */
class CommandDeliveryTest {

    @Command
    record PlainCommand(String id) {
    }

    @Command(delivery = CommandDelivery.OUTBOX)
    record OutboxCommandType(String id) {
    }

    @Test
    @DisplayName("a command type is delivered in the unit of work unless it opts into the log")
    void defaultAndOptIn() {
        assertThat(PlainCommand.class.getAnnotation(Command.class).delivery())
                .isEqualTo(CommandDelivery.IN_UNIT_OF_WORK);
        assertThat(OutboxCommandType.class.getAnnotation(Command.class).delivery()).isEqualTo(CommandDelivery.OUTBOX);
    }
}
