package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HandedOffEvents} validation per specs/004-saga-engine/contracts/event-handoff.md.
 */
class HandedOffEventsTest {

    private static final String UOW = "uow-1";
    private static final String SAGA_TYPE = "com.example.OrderProcessingSaga";
    private static final String SAGA_ID = "s-1";
    private static final Object FIRST = "first";
    private static final Object SECOND = "second";

    @Test
    @DisplayName("a saga-scoped batch carries saga type and id with its events in order")
    void sagaScopedBatch() {
        var batch = new HandedOffEvents(UOW, SAGA_TYPE, SAGA_ID, List.of(FIRST, SECOND));

        assertThat(batch.unitOfWorkId()).isEqualTo(UOW);
        assertThat(batch.sagaType()).isEqualTo(SAGA_TYPE);
        assertThat(batch.sagaId()).isEqualTo(SAGA_ID);
        assertThat(batch.events()).containsExactly(FIRST, SECOND);
    }

    @Test
    @DisplayName("a non-saga batch has neither saga type nor id")
    void nonSagaBatch() {
        var batch = new HandedOffEvents(UOW, null, null, List.of(FIRST));

        assertThat(batch.sagaType()).isNull();
        assertThat(batch.sagaId()).isNull();
        assertThat(batch.events()).containsExactly(FIRST);
    }

    @Test
    @DisplayName("a null unitOfWorkId, null events or a null event is rejected")
    void nullsRejected() {
        assertThatThrownBy(() -> new HandedOffEvents(null, SAGA_TYPE, SAGA_ID, List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandedOffEvents(UOW, SAGA_TYPE, SAGA_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandedOffEvents(UOW, SAGA_TYPE, SAGA_ID, Arrays.asList(FIRST, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an empty batch is rejected")
    void emptyRejected() {
        assertThatThrownBy(() -> new HandedOffEvents(UOW, SAGA_TYPE, SAGA_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exactly one of sagaType and sagaId present is rejected")
    void halfSagaScopedRejected() {
        assertThatThrownBy(() -> new HandedOffEvents(UOW, SAGA_TYPE, null, List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HandedOffEvents(UOW, null, SAGA_ID, List.of(FIRST)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("events is a defensive, unmodifiable copy")
    void eventsCopied() {
        List<Object> original = new ArrayList<>(List.of(FIRST));
        var batch = new HandedOffEvents(UOW, SAGA_TYPE, SAGA_ID, original);

        original.add(SECOND);

        assertThat(batch.events()).containsExactly(FIRST);
        assertThatThrownBy(() -> batch.events().add(SECOND)).isInstanceOf(UnsupportedOperationException.class);
    }
}
