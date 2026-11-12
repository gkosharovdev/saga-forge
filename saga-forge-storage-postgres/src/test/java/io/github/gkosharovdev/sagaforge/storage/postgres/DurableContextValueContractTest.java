package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.test.DurableContextValues;

/**
 * The saga test fixture's durable {@code SagaContext} value rule agrees with what Postgres storage persists
 * ({@code specs/009-saga-test-fixture} contracts/context-value-rule.md, G-009-03): every sample the fixture accepts is
 * encoded and read back by {@link JsonCodec}, the codec {@code PostgresSagaContextStore} writes the {@code values}
 * column with, and every sample the fixture rejects is rejected by it. Runs in the default build; the codec, not the
 * database, decides what is stored.
 */
class DurableContextValueContractTest {

    private static final String FIELD = "values";

    private static final List<Object> ACCEPTED = List.of("text", "", true, false, 0, Integer.MIN_VALUE,
            Long.MAX_VALUE, 2L, 2.5, -0.0, Map.of(), List.of(), Map.of("k", List.of(1, "x", Map.of("n", 3L))),
            List.of(List.of(true), Map.of("deep", Map.of("deeper", 1.25))));

    private static final List<Object> REJECTED = List.of(new BigDecimal("10.00"), BigInteger.ONE, 1.5f,
            (short) 1, (byte) 1, 'c', Double.NaN, Double.POSITIVE_INFINITY, Instant.EPOCH, UUID.randomUUID(),
            new int[] {1}, Map.of(1, "one"), List.of(new BigDecimal("1")), Map.of("price", Instant.EPOCH),
            Arrays.asList("a", null), nullValueMap(), new Object());

    private static Map<String, Object> nullValueMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("k", null);
        return map;
    }

    private static Map<String, Object> asValues(Object value) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("value", value);
        return values;
    }

    @Test
    @DisplayName("every value the fixture accepts, Postgres storage encodes and reads back")
    void acceptedByBoth() {
        for (Object value : ACCEPTED) {
            assertThat(DurableContextValues.isDurable(value)).as("fixture accepts %s", value).isTrue();
            assertThatCode(() -> JsonCodec.encodeObject(FIELD, asValues(value)))
                    .as("Postgres accepts %s", value).doesNotThrowAnyException();
            Object read = JsonCodec.decodeObject(FIELD, JsonCodec.encodeObject(FIELD, asValues(value))).get("value");
            assertThat(normalized(read)).as("read back %s", value).isEqualTo(normalized(value));
        }
    }

    @Test
    @DisplayName("every value the fixture rejects, Postgres storage rejects")
    void rejectedByBoth() {
        for (Object value : REJECTED) {
            assertThat(DurableContextValues.isDurable(value)).as("fixture rejects %s", value).isFalse();
            assertThatThrownBy(() -> JsonCodec.encodeObject(FIELD, asValues(value)))
                    .as("Postgres rejects %s", value).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Integral numbers read back as {@code Integer} when they fit (G-003-07); compare them as {@code long}. */
    private static Object normalized(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, normalized(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DurableContextValueContractTest::normalized).toList();
        }
        return value;
    }
}
