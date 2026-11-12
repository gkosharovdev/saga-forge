package io.github.gkosharovdev.sagaforge.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code SagaContext} value types every supported durable store can persist ({@code specs/009-saga-test-fixture}
 * contracts/context-value-rule.md, FR-019): {@code String}, {@code Boolean}, {@code Integer}, {@code Long}, a finite
 * {@code Double}, a {@code Map} with {@code String} keys and a {@code List}, nested. Today this is Postgres's rule;
 * {@code DurableContextValueContractTest} in {@code saga-forge-storage-postgres} keeps the two in step (G-009-03).
 *
 * <p>The fixture's context store applies it unless {@code SagaFixture.Builder.checkContextValues(false)}.</p>
 */
public final class DurableContextValues {

    /** The supported types, as failure messages name them. */
    public static final String SUPPORTED = "String, Boolean, Integer, Long, finite Double, Map with String keys, List";

    /**
     * The first value the stores cannot persist.
     *
     * @param path        where it sits: a key, then {@code .key} and {@code [index]} for nested values
     * @param description what it is, for example {@code java.math.BigDecimal} or {@code a map key of type Integer}
     */
    record Violation(String path, String description) {
    }

    private DurableContextValues() {
    }

    /** The first value of {@code values}, in iteration order, that is not durable. */
    static Optional<Violation> firstViolation(Map<String, ?> values) {
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Optional<Violation> violation = check(entry.getKey(), entry.getValue());
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether durable storage can persist {@code value} as a {@code SagaContext} value; {@code null} is not durable.
     */
    public static boolean isDurable(Object value) {
        return check("value", value).isEmpty();
    }

    private static Optional<Violation> check(String path, Object value) {
        if (value == null) {
            return Optional.of(new Violation(path, "null"));
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return Optional.empty();
        }
        if (value instanceof Double number) {
            return Double.isFinite(number) ? Optional.empty()
                    : Optional.of(new Violation(path, "java.lang.Double " + number + " (not finite)"));
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    return Optional.of(new Violation(path, "a map key of type "
                            + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName())));
                }
                Optional<Violation> nested = check(path + "." + key, entry.getValue());
                if (nested.isPresent()) {
                    return nested;
                }
            }
            return Optional.empty();
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                Optional<Violation> nested = check(path + "[" + index + "]", list.get(index));
                if (nested.isPresent()) {
                    return nested;
                }
            }
            return Optional.empty();
        }
        return Optional.of(new Violation(path, value.getClass().getName()));
    }
}
