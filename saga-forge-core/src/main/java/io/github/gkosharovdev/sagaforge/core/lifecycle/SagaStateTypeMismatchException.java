package io.github.gkosharovdev.sagaforge.core.lifecycle;

import java.util.Objects;

/**
 * Thrown when a named saga state value exists but is not assignable to the type requested by the
 * caller.
 *
 * <p>Raised by {@code SagaContext.getState} so that type-safe reads report a clear, distinguishable
 * error instead of surfacing an unchecked {@link ClassCastException} or a silently wrong value
 * (FR-003). The exception carries the state name, the requested type, and the actual type of the
 * stored value so callers and logs can pinpoint the mismatch precisely.
 */
public class SagaStateTypeMismatchException extends RuntimeException {

    private final String stateName;
    private final Class<?> expectedType;
    private final Class<?> actualType;

    /**
     * Creates a new type-mismatch exception.
     *
     * @param stateName    name of the state value that was read
     * @param expectedType type requested by the caller
     * @param actualType   runtime type of the stored value
     * @throws NullPointerException if any argument is {@code null}
     */
    public SagaStateTypeMismatchException(String stateName, Class<?> expectedType, Class<?> actualType) {
        super(buildMessage(
            Objects.requireNonNull(stateName, "stateName"),
            Objects.requireNonNull(expectedType, "expectedType"),
            Objects.requireNonNull(actualType, "actualType")));
        this.stateName = stateName;
        this.expectedType = expectedType;
        this.actualType = actualType;
    }

    /**
     * @return name of the state value that was read
     */
    public String getStateName() {
        return stateName;
    }

    /**
     * @return type requested by the caller
     */
    public Class<?> getExpectedType() {
        return expectedType;
    }

    /**
     * @return runtime type of the stored value
     */
    public Class<?> getActualType() {
        return actualType;
    }

    private static String buildMessage(String stateName, Class<?> expectedType, Class<?> actualType) {
        return "Saga state '" + stateName + "' is of type " + actualType.getName()
            + " and cannot be read as " + expectedType.getName();
    }
}
