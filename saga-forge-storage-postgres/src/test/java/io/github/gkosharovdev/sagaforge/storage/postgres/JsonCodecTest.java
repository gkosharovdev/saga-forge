package io.github.gkosharovdev.sagaforge.storage.postgres;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JsonCodec} against the research.md section 9 value set: {@code String}, {@code Boolean},
 * {@code Integer}, {@code Long}, finite {@code Double}, {@code String}-keyed {@code Map}, {@code List}, nested
 * ({@code .specify/memory/memory.md} G-003-07).
 */
class JsonCodecTest {

    private static final String FIELD = "state";

    private static Object roundTrip(Object value) {
        return JsonCodec.decode(FIELD, JsonCodec.encode(FIELD, value));
    }

    @Nested
    @DisplayName("round-trips")
    class RoundTrips {

        @Test
        @DisplayName("scalars from the value set round-trip equal and keep their type")
        void scalarsRoundTrip() {
            long outsideIntRange = Integer.MAX_VALUE + 10L;
            assertThat(roundTrip("text")).isEqualTo("text");
            assertThat(roundTrip(Boolean.TRUE)).isEqualTo(Boolean.TRUE);
            assertThat(roundTrip(false)).isEqualTo(false);
            assertThat(roundTrip(42)).isEqualTo(42);
            assertThat(roundTrip(outsideIntRange)).isInstanceOf(Long.class).isEqualTo(outsideIntRange);
            assertThat(roundTrip(Long.MIN_VALUE)).isEqualTo(Long.MIN_VALUE);
            assertThat(roundTrip(3.25)).isInstanceOf(Double.class).isEqualTo(3.25);
            assertThat(roundTrip(1.0)).isInstanceOf(Double.class).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a nested Map<String, Object> round-trips equal")
        void nestedMapRoundTrips() {
            Map<String, Object> value = Map.of(
                    "orderId", "o-1",
                    "amount", 12.5,
                    "address", Map.of("city", "Sofia", "zip", 1000, "lines", List.of("a", "b")));

            assertThat(JsonCodec.decodeObject(FIELD, JsonCodec.encodeObject(FIELD, value))).isEqualTo(value);
            assertThat(roundTrip(value)).isEqualTo(value);
        }

        @Test
        @DisplayName("a List<Object> round-trips equal")
        void listRoundTrips() {
            List<Object> value = List.of("a", 1, true, 2.5, List.of(), Map.of("k", List.of(1, 2)));

            assertThat(roundTrip(value)).isEqualTo(value);
        }

        @Test
        @DisplayName("empty map and empty list round-trip")
        void emptyContainersRoundTrip() {
            assertThat(JsonCodec.decodeObject(FIELD, JsonCodec.encodeObject(FIELD, Map.of()))).isEmpty();
            assertThat(roundTrip(List.of())).isEqualTo(List.of());
        }

        @Test
        @DisplayName("documented narrowing: a Long inside the int range decodes as Integer")
        void longInsideIntRangeDecodesAsInteger() {
            assertThat(roundTrip(5L)).isInstanceOf(Integer.class).isEqualTo(5);
        }

        @Test
        @DisplayName("strings that need escaping round-trip")
        void escapedStringsRoundTrip() {
            String value = "quote \" backslash \\ newline \n tab \t soh \u0001 bell \u0007 "
                    + "non-ASCII \u00e9 \u65e5\u672c \uD83D\uDE00 slash / end";
            Map<String, Object> asKeyAndValue = Map.of(value, value);

            assertThat(roundTrip(value)).isEqualTo(value);
            assertThat(JsonCodec.decodeObject(FIELD, JsonCodec.encodeObject(FIELD, asKeyAndValue)))
                    .isEqualTo(asKeyAndValue);
        }
    }

    @Nested
    @DisplayName("decoded containers")
    class DecodedContainers {

        @Test
        @DisplayName("decoded maps and lists are unmodifiable at every nesting level")
        @SuppressWarnings("unchecked")
        void unmodifiableAtEveryLevel() {
            Map<String, Object> value = Map.of(
                    "outer", Map.of("inner", List.of(1, Map.of("deep", "x"))),
                    "list", List.of(List.of("a")));

            Map<String, Object> decoded = JsonCodec.decodeObject(FIELD, JsonCodec.encodeObject(FIELD, value));
            Map<String, Object> outer = (Map<String, Object>) decoded.get("outer");
            List<Object> inner = (List<Object>) outer.get("inner");
            Map<String, Object> deep = (Map<String, Object>) inner.get(1);
            List<Object> list = (List<Object>) decoded.get("list");
            List<Object> nestedList = (List<Object>) list.get(0);

            assertThatThrownBy(() -> decoded.put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> outer.put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> inner.add("v")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> deep.put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> list.add("v")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> nestedList.add("v")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("decode of an untyped map or list returns unmodifiable containers")
        @SuppressWarnings("unchecked")
        void untypedDecodeIsUnmodifiable() {
            Map<String, Object> decodedMap = (Map<String, Object>) roundTrip(Map.of("a", 1));
            List<Object> decodedList = (List<Object>) roundTrip(List.of(1));

            assertThatThrownBy(() -> decodedMap.put("b", 2)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> decodedList.add(2)).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("deterministic key order")
    class DeterministicKeyOrder {

        @Test
        @DisplayName("equal maps built in different insertion orders encode to identical text")
        void equalMapsEncodeIdentically() {
            Map<String, Object> innerFirst = new LinkedHashMap<>();
            innerFirst.put("z", 1);
            innerFirst.put("a", 2);
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("zeta", "z");
            first.put("alpha", innerFirst);
            first.put("mid", List.of(innerFirst));

            Map<String, Object> innerSecond = new LinkedHashMap<>();
            innerSecond.put("a", 2);
            innerSecond.put("z", 1);
            Map<String, Object> second = new LinkedHashMap<>();
            second.put("mid", List.of(innerSecond));
            second.put("alpha", innerSecond);
            second.put("zeta", "z");

            assertThat(first).isEqualTo(second);
            assertThat(JsonCodec.encodeObject(FIELD, first)).isEqualTo(JsonCodec.encodeObject(FIELD, second));
            assertThat(JsonCodec.encode(FIELD, first)).isEqualTo(JsonCodec.encode(FIELD, second));
        }
    }

    @Nested
    @DisplayName("rejected on encode")
    class RejectedOnEncode {

        private record Money(int cents) {
        }

        private enum Color { RED }

        private static final class Pojo {
            public String name = "x";
        }

        private void assertRejected(Object value, String expectedTypeName) {
            assertThatThrownBy(() -> JsonCodec.encode(FIELD, value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FIELD)
                    .hasMessageContaining(expectedTypeName);
        }

        @Test
        @DisplayName("Instant is rejected (would decode as an ISO-8601 String)")
        void instantRejected() {
            assertRejected(Instant.parse("2026-11-05T10:15:30Z"), "Instant");
        }

        @Test
        @DisplayName("BigDecimal is rejected (would decode as Double)")
        void bigDecimalRejected() {
            assertRejected(new BigDecimal("12.50"), "BigDecimal");
        }

        @Test
        @DisplayName("a record or POJO is rejected (would decode as Map)")
        void recordAndPojoRejected() {
            assertRejected(new Money(1250), "Money");
            assertRejected(new Pojo(), "Pojo");
        }

        @Test
        @DisplayName("an enum is rejected")
        void enumRejected() {
            assertRejected(Color.RED, "Color");
        }

        @Test
        @DisplayName("NaN and infinities are rejected")
        void nonFiniteDoublesRejected() {
            assertRejected(Double.NaN, "Double");
            assertRejected(Double.POSITIVE_INFINITY, "Double");
            assertRejected(Double.NEGATIVE_INFINITY, "Double");
        }

        @Test
        @DisplayName("a non-String map key is rejected")
        void nonStringKeyRejected() {
            assertRejected(Map.of(1, "one"), "Integer");
        }

        @Test
        @DisplayName("a null value is rejected, at the top level and nested")
        void nullRejected() {
            Map<String, Object> withNullEntry = new HashMap<>();
            withNullEntry.put("missing", null);
            List<Object> withNullElement = new ArrayList<>();
            withNullElement.add("a");
            withNullElement.add(null);

            assertRejected(null, "null");
            assertThatThrownBy(() -> JsonCodec.encodeObject(FIELD, withNullEntry))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FIELD + ".missing")
                    .hasMessageContaining("null");
            assertThatThrownBy(() -> JsonCodec.encode(FIELD, withNullElement))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FIELD + "[1]")
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("a nested unsupported value names its key and index")
        void nestedRejectionNamesPath() {
            Map<String, Object> value = Map.of("address", Map.of("lines", List.of("a", new BigDecimal("1"))));

            assertThatThrownBy(() -> JsonCodec.encodeObject(FIELD, value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FIELD + ".address.lines[1]")
                    .hasMessageContaining("BigDecimal");
        }

        @Test
        @DisplayName("encodeObject rejects a null map")
        void nullObjectRejected() {
            assertThatThrownBy(() -> JsonCodec.encodeObject(FIELD, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(FIELD);
        }
    }

    @Nested
    @DisplayName("rejected on decode")
    class RejectedOnDecode {

        @Test
        @DisplayName("malformed text throws IllegalStateException naming the field, caused by JacksonException")
        void malformedTextRejected() {
            assertThatThrownBy(() -> JsonCodec.decode(FIELD, "{not json"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(FIELD)
                    .hasCauseInstanceOf(JacksonException.class);
            assertThatThrownBy(() -> JsonCodec.decodeObject(FIELD, "{\"a\": [1, 2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(FIELD)
                    .hasCauseInstanceOf(JacksonException.class);
        }

        @Test
        @DisplayName("decodeObject of text that is not a JSON object throws IllegalStateException")
        void nonObjectRejected() {
            assertThatThrownBy(() -> JsonCodec.decodeObject(FIELD, "[1, 2]"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(FIELD);
        }
    }
}
