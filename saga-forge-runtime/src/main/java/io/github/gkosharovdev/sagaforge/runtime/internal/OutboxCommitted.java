package io.github.gkosharovdev.sagaforge.runtime.internal;

import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.OutgoingMessages;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;

/**
 * Calls {@link Outbox#committed(OutgoingMessages)} for an {@code IN_TRANSACTION} outbox after its unit of work
 * committed and released the instance lock ({@code specs/007-in-memory-transport} research §2). A failure is logged
 * and changes nothing.
 */
final class OutboxCommitted {

    private OutboxCommitted() {
    }

    static void notify(EngineSettings settings, Optional<OutgoingMessages> batch, Diagnostics diagnostics,
            Correlation correlation) {
        if (settings.outboxTiming() != Outbox.Timing.IN_TRANSACTION || batch.isEmpty()) {
            return;
        }
        try {
            settings.outbox().committed(batch.get());
        } catch (RuntimeException failure) {
            diagnostics.outboxCommittedFailed(correlation, failure);
        }
    }
}
