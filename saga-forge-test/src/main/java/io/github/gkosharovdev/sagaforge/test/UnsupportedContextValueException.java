package io.github.gkosharovdev.sagaforge.test;

/**
 * A step stored a {@code SagaContext} value that durable storage cannot persist ({@code specs/009-saga-test-fixture}
 * FR-019). Thrown by the fixture's context store, so the step fails where it wrote the value; the fixture then fails
 * the {@code given}/{@code when} call with a {@link SagaExpectationError} that carries this exception as its cause.
 * Turn the check off with {@code SagaFixture.Builder.checkContextValues(false)}.
 */
public final class UnsupportedContextValueException extends IllegalArgumentException {

    private final String sagaType;
    private final String sagaId;
    private final String keyPath;
    private final String valueType;

    UnsupportedContextValueException(String sagaType, String sagaId, String keyPath, String valueType) {
        super("SagaContext value '" + keyPath + "' of saga " + sagaType + " (" + sagaId + ") is " + valueType
                + ", which durable storage cannot persist (supported: " + DurableContextValues.SUPPORTED + ")");
        this.sagaType = sagaType;
        this.sagaId = sagaId;
        this.keyPath = keyPath;
        this.valueType = valueType;
    }

    /** Fully qualified class name of the saga, or {@code "unknown"} before its first write. */
    public String sagaType() {
        return sagaType;
    }

    public String sagaId() {
        return sagaId;
    }

    /** The key, then {@code .key} and {@code [index]} for nested values, for example {@code items[2].price}. */
    public String keyPath() {
        return keyPath;
    }

    /** The offending value's type, or what is wrong with it (a non-{@code String} map key, a non-finite double). */
    public String valueType() {
        return valueType;
    }
}
