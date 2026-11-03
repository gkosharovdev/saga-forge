package io.github.gkosharovdev.sagaforge.core.spi;

import java.util.Optional;

/**
 * Engine-facing binding of a {@link DomainEventRecorder} to the calling thread.
 *
 * <p>The engine wraps each unit of work in {@link #runWith(DomainEventRecorder, Runnable)}, so that
 * {@link io.github.gkosharovdev.sagaforge.core.event.DomainEvents#record(Object)} called from any depth on that
 * thread (aggregate, domain service, handler) reaches the unit of work's recorder. The binding is held in a
 * {@link ThreadLocal}: it is not visible from other threads, including threads the handler starts itself.
 *
 * <p>Bindings nest: an inner {@code runWith} shadows the outer recorder for its duration and restores it on exit,
 * whether {@code work} returns normally or throws.
 */
public final class DomainEventScope {

    private static final ThreadLocal<DomainEventRecorder> CURRENT = new ThreadLocal<>();

    private DomainEventScope() {
    }

    /**
     * Binds {@code recorder} to the calling thread while {@code work} runs, then restores any recorder that was
     * bound before (or clears the binding if there was none). Exceptions thrown by {@code work} propagate
     * unchanged after the previous binding has been restored.
     *
     * @param recorder the recorder to bind; must not be {@code null}
     * @param work     the work to run under the binding; must not be {@code null}
     * @throws IllegalArgumentException if {@code recorder} or {@code work} is {@code null}
     */
    public static void runWith(DomainEventRecorder recorder, Runnable work) {
        if (recorder == null) {
            throw new IllegalArgumentException("recorder must not be null");
        }
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }
        DomainEventRecorder previous = CURRENT.get();
        CURRENT.set(recorder);
        try {
            work.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Returns the recorder bound to the calling thread, if any.
     *
     * <p><strong>Framework-internal.</strong> This accessor is public only because
     * {@link io.github.gkosharovdev.sagaforge.core.event.DomainEvents} lives in another package. Client code
     * records events through {@code DomainEvents.record} and should not call this method.
     *
     * @return the currently bound recorder, or {@link Optional#empty()} if no unit of work is active on this
     *     thread
     */
    public static Optional<DomainEventRecorder> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
