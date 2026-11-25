package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.deadline.OpenDeadline;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandlingException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.DeadlineFixture;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentAuthorized;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentDeclined;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentWindowSaga;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User Story 3: a deadline never runs a step of an instance that stopped running or no longer holds it open
 * (specs/017-saga-deadlines spec.md US3, FR-014, Property 42; tasks.md T031).
 */
class DeadlineIgnoredTest {

    private final DeadlineFixture fixture = new DeadlineFixture();

    private DeadlineReached windowOf(String sagaId) {
        OpenDeadline open = fixture.committed(sagaId).openDeadlines().get(PaymentWindowSaga.WINDOW);
        return new DeadlineReached(PaymentWindowSaga.class.getName(), sagaId, PaymentWindowSaga.WINDOW,
                open.deadlineId(), open.dueAt(), open.startedByStep());
    }

    private void assertIgnored(SagaHandlingResult result, String reason) {
        assertThat(result.outcome()).isEqualTo(HandlingOutcome.IGNORED);
        assertThat(PaymentWindowSaga.RECEIVED).isEmpty();
        assertThat(fixture.logger().byEvent("saga.deadline.ignored")).last()
                .satisfies(line -> assertThat(line.keys()).containsEntry("reason", reason));
    }

    @Test
    @DisplayName("a deadline delivered after the saga completed is ignored and writes nothing")
    void completed() {
        String sagaId = fixture.placedAndReserved("o-1", "PT30M");
        DeadlineReached window = windowOf(sagaId);
        fixture.receive(new PaymentAuthorized("o-1"));
        SagaInstance before = fixture.committed(sagaId);

        assertIgnored(fixture.deliver(window), "NOT_RUNNING");
        assertThat(fixture.committed(sagaId)).isEqualTo(before);
        assertThat(before.status()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("a deadline delivered after the saga compensated is ignored; the instance is not compensated twice")
    void compensated() {
        String sagaId = fixture.placedAndReserved("o-1", "PT30M");
        DeadlineReached window = windowOf(sagaId);
        fixture.advance(Duration.ofMinutes(30));
        int handled = fixture.stock().handled().size();

        assertThat(fixture.deliver(window).outcome()).isEqualTo(HandlingOutcome.IGNORED);
        assertThat(fixture.stock().handled()).hasSize(handled);
        assertThat(PaymentWindowSaga.RECEIVED).hasSize(1);
    }

    @Test
    @DisplayName("a cancelled deadline delivered anyway (leased before the cancel) is ignored as NOT_OPEN")
    void cancelledButDelivered() {
        String sagaId = fixture.placedAndReserved("o-1", "PT30M");
        DeadlineReached window = windowOf(sagaId);
        fixture.outbox().lease(window.deadlineId());
        fixture.receive(new PaymentDeclined("o-1"));

        assertIgnored(fixture.deliver(window), "NOT_OPEN");
        assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.RUNNING);
    }

    @Test
    @DisplayName("a deadline for a missing instance is ignored")
    void missingInstance() {
        DeadlineReached stray = new DeadlineReached(PaymentWindowSaga.class.getName(), "saga-404",
                PaymentWindowSaga.WINDOW, "saga-404:deadline:paymentWindow:uow-9", DeadlineFixture.START, "x");

        assertIgnored(fixture.deliver(stray), "NOT_RUNNING");
    }

    @Test
    @DisplayName("a deadline for a saga type this process does not run is not handled, so it is kept")
    void unknownSagaType() {
        DeadlineReached stray = new DeadlineReached("com.example.Unknown", "saga-1", "w", "saga-1:deadline:w:uow-1",
                DeadlineFixture.START, "x");

        assertThatThrownBy(() -> fixture.deliver(stray)).isInstanceOf(EventHandlingException.class)
                .hasMessageContaining("com.example.Unknown");
    }
}
