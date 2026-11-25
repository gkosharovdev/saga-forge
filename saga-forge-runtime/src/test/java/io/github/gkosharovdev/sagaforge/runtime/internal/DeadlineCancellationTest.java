package io.github.gkosharovdev.sagaforge.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.DeadlineFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentWindowSaga;
import io.github.gkosharovdev.sagaforge.runtime.support.SchedulingOutbox.State;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every write that leaves {@code RUNNING} through the runtime cancels the deadlines still open (Property 41;
 * specs/017-saga-deadlines tasks.md T032). The lifecycle-level rule for each exit is in {@code DeadlinePlanTest}.
 */
class DeadlineCancellationTest {

    private static final class Declined extends RuntimeException implements FinalFailure {
        private static final long serialVersionUID = 1L;

        Declined() {
            super("declined");
        }
    }

    private final DeadlineFixture fixture = new DeadlineFixture();

    private String windowOpen() {
        return fixture.placedAndReserved("o-1", "PT1H");
    }

    private String windowId(String sagaId) {
        return fixture.committed(sagaId).openDeadlines().get(PaymentWindowSaga.WINDOW).deadlineId();
    }

    @Test
    @DisplayName("the FAILED write after an UNCLASSIFIED failure cancels the deadlines left open")
    void failedWrite() {
        String sagaId = windowOpen();
        String window = windowId(sagaId);
        fixture.stock().failReminders(new IllegalStateException("boom"));

        fixture.advance(Duration.ofMinutes(10));

        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.FAILED);
        assertThat(fixture.committed(sagaId).openDeadlines()).isEmpty();
        assertThat(fixture.outbox().state(window)).isEqualTo(State.CANCELLED);
        assertThat(fixture.advance(Duration.ofHours(2))).isEmpty();
        assertThat(PaymentWindowSaga.RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("a FINAL failure's compensation start cancels the deadlines left open")
    void finalFailureCompensates() {
        String sagaId = windowOpen();
        String window = windowId(sagaId);
        fixture.stock().failReminders(new Declined());

        fixture.advance(Duration.ofMinutes(10));

        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(fixture.committed(sagaId).openDeadlines()).isEmpty();
        assertThat(fixture.outbox().state(window)).isEqualTo(State.CANCELLED);
        assertThat(fixture.advance(Duration.ofHours(2))).isEmpty();
        assertThat(PaymentWindowSaga.RECEIVED).isEmpty();
    }
}
