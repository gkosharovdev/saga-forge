package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OutgoingMessages} validation per specs/004-saga-engine/contracts/event-handoff.md and, for commands,
 * specs/007-in-memory-transport/contracts/core-spi.md.
 */
class OutgoingMessagesTest {

    private static final String UOW = "uow-1";
    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final String SAGA_ID = "s-1";
    private static final Object FIRST = "first";
    private static final Object SECOND = "second";

    @Test
    @DisplayName("a saga-scoped batch carries saga type and id with its events in order")
    void sagaScopedBatch() {
        var batch = new OutgoingMessages(UOW, SAGA_TYPE, SAGA_ID, List.of(FIRST, SECOND));

        assertThat(batch.unitOfWorkId()).isEqualTo(UOW);
        assertThat(batch.sagaType()).isEqualTo(SAGA_TYPE);
        assertThat(batch.sagaId()).isEqualTo(SAGA_ID);
        assertThat(batch.events()).containsExactly(FIRST, SECOND);
    }

    @Test
    @DisplayName("a non-saga batch has neither saga type nor id")
    void nonSagaBatch() {
        var batch = new OutgoingMessages(UOW, null, null, List.of(FIRST));

        assertThat(batch.sagaType()).isNull();
        assertThat(batch.sagaId()).isNull();
        assertThat(batch.events()).containsExactly(FIRST);
    }

    @Test
    @DisplayName("a null unitOfWorkId, null events or a null event is rejected")
    void nullsRejected() {
        assertThatThrownBy(() -> new OutgoingMessages(null, SAGA_TYPE, SAGA_ID, List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutgoingMessages(UOW, SAGA_TYPE, SAGA_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutgoingMessages(UOW, SAGA_TYPE, SAGA_ID, Arrays.asList(FIRST, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an empty batch is rejected")
    void emptyRejected() {
        assertThatThrownBy(() -> new OutgoingMessages(UOW, SAGA_TYPE, SAGA_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exactly one of sagaType and sagaId present is rejected")
    void halfSagaScopedRejected() {
        assertThatThrownBy(() -> new OutgoingMessages(UOW, SAGA_TYPE, null, List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutgoingMessages(UOW, null, SAGA_ID, List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("events is a defensive, unmodifiable copy")
    void eventsCopied() {
        List<Object> original = new ArrayList<>(List.of(FIRST));
        var batch = new OutgoingMessages(UOW, SAGA_TYPE, SAGA_ID, original);

        original.add(SECOND);

        assertThat(batch.events()).containsExactly(FIRST);
        assertThatThrownBy(() -> batch.events().add(SECOND)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a batch may carry outbox commands, with or without events, in send order")
    void commandsCarried() {
        var first = new OutgoingCommand(CommandId.of(SAGA_ID, "step", "com.example.Pay", 1), "pay-1");
        var second = new OutgoingCommand(CommandId.of(SAGA_ID, "step", "com.example.Pay", 2), "pay-2");

        var onlyCommands = new OutgoingMessages(UOW, SAGA_TYPE, SAGA_ID, List.of(), List.of(first, second));
        var both = new OutgoingMessages(UOW, SAGA_TYPE, SAGA_ID, List.of(FIRST), List.of(first));

        assertThat(onlyCommands.events()).isEmpty();
        assertThat(onlyCommands.commands()).containsExactly(first, second);
        assertThat(both.events()).containsExactly(FIRST);
        assertThat(both.commands()).containsExactly(first);
    }

    @Test
    @DisplayName("the four-argument constructor carries no commands")
    void eventsOnlyConstructor() {
        assertThat(new OutgoingMessages(UOW, null, null, List.of(FIRST)).commands()).isEmpty();
    }

    @Test
    @DisplayName("no events and no commands, null commands or a null command is rejected; commands are copied")
    void commandRules() {
        var command = new OutgoingCommand(CommandId.of(SAGA_ID, "step", "com.example.Pay", 1), "pay");
        assertThatThrownBy(() -> new OutgoingMessages(UOW, null, null, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutgoingMessages(UOW, null, null, List.of(FIRST), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutgoingMessages(UOW, null, null, List.of(), Arrays.asList(command, null)))
                .isInstanceOf(IllegalArgumentException.class);
        List<OutgoingCommand> source = new ArrayList<>(List.of(command));
        var batch = new OutgoingMessages(UOW, null, null, List.of(), source);
        source.clear();
        assertThat(batch.commands()).containsExactly(command);
        assertThatThrownBy(() -> batch.commands().add(command)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an OutgoingCommand needs an identity and a command")
    void outgoingCommandRules() {
        assertThatThrownBy(() -> new OutgoingCommand(null, "pay")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutgoingCommand(CommandId.unscoped("com.example.Pay"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
