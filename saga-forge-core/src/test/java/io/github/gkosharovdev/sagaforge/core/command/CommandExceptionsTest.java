package io.github.gkosharovdev.sagaforge.core.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandExceptionsTest {

    record ReserveStock(String orderId) {
    }

    static class Inventory {
    }

    @Nested
    @DisplayName("NoCommandHandlerException")
    class NoHandler {

        @Test
        @DisplayName("exposes the command type and names it in the message")
        void exposesCommandType() {
            NoCommandHandlerException ex = new NoCommandHandlerException(ReserveStock.class);

            assertThat(ex).isInstanceOf(RuntimeException.class);
            assertThat(ex.getCommandType()).isEqualTo(ReserveStock.class);
            assertThat(ex.getMessage()).contains(ReserveStock.class.getName());
        }

        @Test
        @DisplayName("rejects a null command type")
        void rejectsNull() {
            assertThatThrownBy(() -> new NoCommandHandlerException(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("commandType");
        }
    }

    @Nested
    @DisplayName("AggregateNotFoundException")
    class NotFound {

        @Test
        @DisplayName("exposes the aggregate type and id and names both in the message")
        void exposesTypeAndId() {
            AggregateNotFoundException ex = new AggregateNotFoundException(Inventory.class, "sku-42");

            assertThat(ex).isInstanceOf(RuntimeException.class);
            assertThat(ex.getAggregateType()).isEqualTo(Inventory.class);
            assertThat(ex.getAggregateId()).isEqualTo("sku-42");
            assertThat(ex.getMessage()).contains(Inventory.class.getName()).contains("sku-42");
        }

        @Test
        @DisplayName("rejects a null aggregate type")
        void rejectsNullType() {
            assertThatThrownBy(() -> new AggregateNotFoundException(null, "sku-42"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("aggregateType");
        }

        @Test
        @DisplayName("rejects a null aggregate id")
        void rejectsNullId() {
            assertThatThrownBy(() -> new AggregateNotFoundException(Inventory.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("aggregateId");
        }
    }
}
