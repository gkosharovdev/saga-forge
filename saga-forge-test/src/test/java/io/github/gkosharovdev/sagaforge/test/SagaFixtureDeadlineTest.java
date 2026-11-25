package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.outcome.SagaCompensated;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking.BookingPaid;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking.BookingRequested;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking.BookingSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Booking.ReleaseSeat;

/** Time in the fixture: deadlines come due as the clock advances (specs/017-saga-deadlines contracts/fixture.md). */
class SagaFixtureDeadlineTest {

    private final SagaFixture fixture = SagaFixture.of(BookingSaga.class, Booking.Seat.class).build();

    @Test
    @DisplayName("a started deadline is open, due at the clock plus its duration")
    void openDeadline() {
        fixture.when(new BookingRequested("b-1", "PT30M"))
                .expectOpenDeadline(Booking.HOLD)
                .expectStatus(SagaStatus.RUNNING);

        assertThat(fixture.instance(BookingSaga.class, "b-1").openDeadlines().get(Booking.HOLD).dueAt())
                .isEqualTo(FixtureClock.DEFAULT_START.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("before it is due nothing happens; once due the timeout step runs and compensates")
    void expires() {
        fixture.given(new BookingRequested("b-1", "PT30M"))
                .whenTimeElapses(Duration.ofMinutes(29))
                .expectNoCommands()
                .instance(BookingSaga.class, "b-1")
                .expectOpenDeadline(Booking.HOLD)
                .expectStatus(SagaStatus.RUNNING);

        fixture.whenTimeElapses(Duration.ofMinutes(1))
                .expectDeadlineExpired(Booking.HOLD)
                .expectCompensatedAfter(Booking.EXPIRED)
                .expectCommands(new ReleaseSeat("b-1"))
                .expectNoOpenDeadline(Booking.HOLD)
                .expectStatus(SagaStatus.COMPENSATED)
                .expectOutcomeEvent(SagaCompensated.class);
        assertThat(fixture.instance(BookingSaga.class, "b-1").compensationTrigger().orElseThrow().kind())
                .isEqualTo(CompensationTriggerKind.DEADLINE_EXPIRED);
    }

    @Test
    @DisplayName("the step a deadline waits for cancels it; later time passes without a timeout")
    void cancelledInTime() {
        fixture.given(new BookingRequested("b-1", "PT30M"))
                .when(new BookingPaid("b-1"))
                .expectNoOpenDeadline(Booking.HOLD)
                .expectStatus(SagaStatus.COMPLETED);

        fixture.whenTimeElapses(Duration.ofHours(2)).expectNoCommands();
        assertThat(fixture.instance(BookingSaga.class, "b-1").status()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    @DisplayName("whenTimeReaches sets the clock; given time passes without counting")
    void timeReachesAndGivenTime() {
        fixture.given(new BookingRequested("b-1", "PT30M"), new BookingRequested("b-2", "PT2H"))
                .givenTimeElapses(Duration.ofHours(1))
                .whenTimeReaches(FixtureClock.DEFAULT_START.plus(Duration.ofHours(2)))
                .expectDeadlineExpired(Booking.HOLD)
                .expectCommands(new ReleaseSeat("b-2"));

        assertThat(fixture.clock().instant()).isEqualTo(FixtureClock.DEFAULT_START.plus(Duration.ofHours(2)));
        assertThat(fixture.instance(BookingSaga.class, "b-1").status()).isEqualTo(SagaStatus.COMPENSATED);
    }

    @Test
    @DisplayName("advancing the clock directly delivers nothing until the next whenTime...")
    void clockAloneDeliversNothing() {
        fixture.given(new BookingRequested("b-1", "PT30M"));
        fixture.clock().advance(Duration.ofHours(1));
        assertThat(fixture.instance(BookingSaga.class, "b-1").status()).isEqualTo(SagaStatus.RUNNING);

        fixture.whenTimeElapses(Duration.ZERO).expectDeadlineExpired(Booking.HOLD);
    }

    @Test
    @DisplayName("mismatches name the deadline; time never moves backwards")
    void mismatches() {
        Then then = fixture.when(new BookingRequested("b-1", "PT30M"));

        assertThatThrownBy(() -> then.expectNoOpenDeadline(Booking.HOLD)).isInstanceOf(SagaExpectationError.class)
                .hasMessageContaining("expected deadline 'hold'");
        assertThatThrownBy(() -> then.expectDeadlineExpired(Booking.HOLD)).isInstanceOf(SagaExpectationError.class);
        assertThatThrownBy(() -> then.expectDeadlineIgnored(Booking.HOLD)).isInstanceOf(SagaExpectationError.class);
        assertThatThrownBy(() -> fixture.whenTimeReaches(Instant.parse("2025-01-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.whenTimeElapses(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
