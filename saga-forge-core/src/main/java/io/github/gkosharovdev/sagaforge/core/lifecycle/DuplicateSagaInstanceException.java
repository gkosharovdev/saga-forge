package io.github.gkosharovdev.sagaforge.core.lifecycle;

import java.util.Objects;

/**
 * Thrown when a request to start a saga instance collides with an already-active (non-terminal)
 * instance of the same saga type correlated to the same association property/value pair.
 *
 * <p>Raised by {@link SagaLifecycle#start(Class, String, String)} so that a duplicate start is reported as a clear,
 * distinguishable conflict rather than silently creating a second instance or silently reusing the
 * existing one (FR-006). The exception carries the saga type and the correlation (association
 * property and value) that collided so callers and logs can pinpoint the conflict precisely.
 */
public class DuplicateSagaInstanceException extends RuntimeException {

    private final Class<?> sagaType;
    private final String associationProperty;
    private final String associationValue;

    /**
     * Creates a new duplicate-instance exception.
     *
     * @param sagaType            saga type the start request targeted
     * @param associationProperty association property used to correlate the instance
     * @param associationValue    association value already held by an active instance
     * @throws NullPointerException if any argument is {@code null}
     */
    public DuplicateSagaInstanceException(Class<?> sagaType, String associationProperty, String associationValue) {
        super(buildMessage(
            Objects.requireNonNull(sagaType, "sagaType"),
            Objects.requireNonNull(associationProperty, "associationProperty"),
            Objects.requireNonNull(associationValue, "associationValue")));
        this.sagaType = sagaType;
        this.associationProperty = associationProperty;
        this.associationValue = associationValue;
    }

    /**
     * @return saga type the start request targeted
     */
    public Class<?> getSagaType() {
        return sagaType;
    }

    /**
     * @return association property used to correlate the instance
     */
    public String getAssociationProperty() {
        return associationProperty;
    }

    /**
     * @return association value already held by an active instance
     */
    public String getAssociationValue() {
        return associationValue;
    }

    private static String buildMessage(Class<?> sagaType, String associationProperty, String associationValue) {
        return "An active instance of saga " + sagaType.getName() + " already exists for "
            + associationProperty + " = '" + associationValue + "'";
    }
}
