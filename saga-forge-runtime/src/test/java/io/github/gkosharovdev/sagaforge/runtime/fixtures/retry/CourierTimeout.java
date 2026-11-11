package io.github.gkosharovdev.sagaforge.runtime.fixtures.retry;

/** An unmarked failure {@link RetrySaga} lists in {@code retryOn}. */
public final class CourierTimeout extends RuntimeException {

    public CourierTimeout(String message) {
        super(message);
    }
}
