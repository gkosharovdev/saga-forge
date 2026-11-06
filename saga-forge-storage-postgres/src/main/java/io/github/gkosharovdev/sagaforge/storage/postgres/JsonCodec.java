package io.github.gkosharovdev.sagaforge.storage.postgres;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes this module's {@code JSONB} columns ({@code saga_instance.state},
 * {@code saga_instance.steps}, {@code saga_context.values}, {@code saga_lifecycle_history.touched_aggregates})
 * through one shared Jackson 3 {@link JsonMapper} (research.md section 9).
 *
 * <p>The accepted value set is {@code String}, {@code Boolean}, {@code Integer}, {@code Long}, finite
 * {@code Double}, {@code Map} with {@code String} keys, and {@code List}, nested. Anything else is rejected on
 * encode with an {@link IllegalArgumentException} naming the field and the nested key or index, before
 * Jackson sees it: without type metadata it would decode as a different type ({@code Instant} as an
 * ISO-8601 {@code String}, {@code BigDecimal} as {@code Double}, a record or POJO as a {@code Map}).
 * Integral numbers decode as {@code Integer} when they fit, else {@code Long}, so a {@code Long} inside the
 * {@code int} range reads back as {@code Integer} ({@code .specify/memory/memory.md} G-003-07).</p>
 *
 * <p>Decoding is untyped (no default typing, so stored text never names a Java class) and returns deep
 * unmodifiable copies. Map entries are written in key order, so equal maps always encode to identical
 * text. {@code steps} and {@code touched_aggregates} are mapped to plain maps and lists by
 * {@code SagaInstanceRows} before they reach this codec.</p>
 */
final class JsonCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final String SUPPORTED =
            "supported: String, Boolean, Integer, Long, finite Double, Map with String keys, List";

    private JsonCodec() {
    }

    /**
     * @param field the column or field being encoded, used in error messages
     * @param value a value from the accepted value set
     * @return the JSON text
     * @throws IllegalArgumentException if {@code value} (or anything nested in it) is outside the value set,
     *                                  or if Jackson fails to write it
     */
    static String encode(String field, Object value) {
        requireSupported(field, value);
        return write(field, value);
    }

    /**
     * @param field the column or field being encoded, used in error messages
     * @param value a {@code String}-keyed map whose values are in the accepted value set
     * @return the JSON object text
     * @throws IllegalArgumentException if {@code value} is {@code null}, holds anything outside the value
     *                                  set, or if Jackson fails to write it
     */
    static String encodeObject(String field, Map<String, ?> value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        requireSupported(field, value);
        return write(field, value);
    }

    /**
     * @param field the column or field being decoded, used in error messages
     * @param json  stored JSON text
     * @return the decoded value: a scalar, or a deep unmodifiable {@code Map<String, Object>} or
     *         {@code List<Object>}
     * @throws IllegalStateException if {@code json} is {@code null} or malformed, or holds a value outside the
     *                               value set (a JSON {@code null}, or a number too large for {@code Long})
     */
    static Object decode(String field, String json) {
        if (json == null) {
            throw new IllegalStateException(field + " has no stored JSON");
        }
        Object raw;
        try {
            raw = MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalStateException(field + " holds malformed JSON: " + e.getOriginalMessage(), e);
        }
        return freeze(field, raw);
    }

    /**
     * @param field the column or field being decoded, used in error messages
     * @param json  stored JSON object text
     * @return the decoded deep unmodifiable map
     * @throws IllegalStateException as {@link #decode(String, String)}, or if the text is not a JSON object
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> decodeObject(String field, String json) {
        Object decoded = decode(field, json);
        if (!(decoded instanceof Map)) {
            throw new IllegalStateException(
                    field + " must hold a JSON object, but holds a " + decoded.getClass().getName());
        }
        return (Map<String, Object>) decoded;
    }

    private static String write(String field, Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(field + " could not be encoded as JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static void requireSupported(String path, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(path + " must not be null (null values are not stored)");
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long) {
            return;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException(
                        path + " must be a finite java.lang.Double, but is " + number);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    String keyType = entry.getKey() == null ? "null" : entry.getKey().getClass().getName();
                    throw new IllegalArgumentException(
                            path + " has a map key of type " + keyType + "; map keys must be java.lang.String");
                }
                requireSupported(path + "." + key, entry.getValue());
            }
            return;
        }
        if (value instanceof List<?> list) {
            int index = 0;
            for (Object element : list) {
                requireSupported(path + "[" + index + "]", element);
                index++;
            }
            return;
        }
        throw new IllegalArgumentException(
                path + " has unsupported value type " + value.getClass().getName() + "; " + SUPPORTED);
    }

    private static Object freeze(String path, Object raw) {
        if (raw == null) {
            throw new IllegalStateException(path + " holds a JSON null, which is outside the accepted value set");
        }
        if (raw instanceof String || raw instanceof Boolean || raw instanceof Integer || raw instanceof Long
                || raw instanceof Double) {
            return raw;
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = (String) entry.getKey();
                copy.put(key, freeze(path + "." + key, entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (raw instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            int index = 0;
            for (Object element : list) {
                copy.add(freeze(path + "[" + index + "]", element));
                index++;
            }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalStateException(path + " holds a value of type " + raw.getClass().getName()
                + ", which is outside the accepted value set; " + SUPPORTED);
    }
}
