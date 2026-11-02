package io.github.gkosharovdev.sagaforge.core.lifecycle;

import java.util.Objects;

/**
 * Thrown when a field-level update is attempted on a named saga state value that already exists but
 * is a scalar (non-structured) value.
 *
 * <p>Raised by {@code SagaContext.updateStateAttribute} before any mutation takes place, so the
 * existing scalar value is left untouched rather than partially overwritten (FR-002c). The exception
 * carries the state name, the field the caller attempted to update, and the actual type of the
 * existing value so callers and logs can pinpoint the conflict precisely.
 */
public class NonStructuredStateValueException extends RuntimeException {

    private final String stateName;
    private final String fieldName;
    private final Class<?> actualType;

    /**
     * Creates a new non-structured-value exception.
     *
     * @param stateName  name of the state value targeted by the field-level update
     * @param fieldName  field the caller attempted to update
     * @param actualType runtime type of the existing scalar value
     * @throws NullPointerException if any argument is {@code null}
     */
    public NonStructuredStateValueException(String stateName, String fieldName, Class<?> actualType) {
        super(buildMessage(
            Objects.requireNonNull(stateName, "stateName"),
            Objects.requireNonNull(fieldName, "fieldName"),
            Objects.requireNonNull(actualType, "actualType")));
        this.stateName = stateName;
        this.fieldName = fieldName;
        this.actualType = actualType;
    }

    /**
     * @return name of the state value targeted by the field-level update
     */
    public String getStateName() {
        return stateName;
    }

    /**
     * @return field the caller attempted to update
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * @return runtime type of the existing scalar value
     */
    public Class<?> getActualType() {
        return actualType;
    }

    private static String buildMessage(String stateName, String fieldName, Class<?> actualType) {
        return "Cannot update field '" + fieldName + "' of saga state '" + stateName
            + "': existing value is a non-structured " + actualType.getName();
    }
}
