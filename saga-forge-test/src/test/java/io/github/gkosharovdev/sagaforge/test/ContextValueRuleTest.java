package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.test.fixtures.LedgerSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.LedgerSaga.ValueBooked;

/** FR-019 and SC-007: SagaContext values durable storage cannot persist fail the test (tasks.md T048). */
class ContextValueRuleTest {

    private static SagaExpectationError rejected(Object value) {
        SagaFixture fixture = SagaFixture.of(LedgerSaga.class).build();
        SagaExpectationError error = catchThrowableOfType(SagaExpectationError.class,
                () -> fixture.when(new ValueBooked("l-1", "orderAmount", value)));
        assertThat(error).as("storing %s should fail the test", value).isNotNull();
        return error;
    }

    @Test
    @DisplayName("SC-007: a BigDecimal fails the test, naming saga, association value, step, key and type")
    void bigDecimal() {
        SagaExpectationError error = rejected(new BigDecimal("10.00"));

        assertThat(error.getMessage()).isEqualTo("""
                LedgerSaga[ledgerId=l-1] (saga-1), when ValueBooked[ledgerId=l-1, key=orderAmount, value=10.00]:
                  step booked stored SagaContext value 'orderAmount' of type java.math.BigDecimal,
                  which durable storage cannot persist (supported: String, Boolean, Integer, Long, finite Double, \
                Map with String keys, List).
                  Store it as a String (for example amount.toPlainString()), or build the fixture with \
                checkContextValues(false).""");
        assertThat(error.getCause()).isInstanceOfSatisfying(UnsupportedContextValueException.class, cause -> {
            assertThat(cause.keyPath()).isEqualTo("orderAmount");
            assertThat(cause.valueType()).isEqualTo("java.math.BigDecimal");
            assertThat(cause.sagaId()).isEqualTo("saga-1");
            assertThat(cause.sagaType()).isEqualTo(LedgerSaga.class.getName());
        });
    }

    @Test
    @DisplayName("nested values name their key path; non-String keys, NaN and infinity are rejected")
    void nestedAndSpecialValues() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("price", Instant.EPOCH);
        assertThat(rejected(Map.of("items", List.of("a", "b", item))).getMessage())
                .contains("'orderAmount.items[2].price' of type java.time.Instant");
        assertThat(rejected(Map.of(1, "one")).getMessage()).contains("a map key of type java.lang.Integer");
        assertThat(rejected(Double.NaN).getMessage()).contains("not finite");
        assertThat(rejected(Double.POSITIVE_INFINITY).getMessage()).contains("not finite");
        assertThat(rejected(1.5f).getMessage()).contains("java.lang.Float");
    }

    @Test
    @DisplayName("every durable type is accepted")
    void durableTypes() {
        List<Object> durable = List.of("text", true, 1, 2L, 2.5, Map.of("k", List.of(1, "x", Map.of("n", 3L))),
                List.of());
        SagaFixture fixture = SagaFixture.of(LedgerSaga.class).build();
        int ledger = 0;
        for (Object value : durable) {
            fixture.when(new ValueBooked("l-" + ++ledger, "value", value))
                    .expectStatus(SagaStatus.RUNNING)
                    .expectContext("value", value);
            assertThat(DurableContextValues.isDurable(value)).isTrue();
        }
        assertThat(DurableContextValues.isDurable(null)).isFalse();
    }

    @Test
    @DisplayName("checkContextValues(false) lets the BigDecimal through")
    void checkOff() {
        SagaFixture.of(LedgerSaga.class).checkContextValues(false).build()
                .when(new ValueBooked("l-1", "orderAmount", new BigDecimal("10.00")))
                .expectStatus(SagaStatus.RUNNING)
                .expectContext("orderAmount", new BigDecimal("10.00"));
    }
}
