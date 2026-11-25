package io.github.gkosharovdev.sagaforge.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline.PaymentWindowSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.schedule.ShipmentSaga;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The per-saga deadline model read at startup (specs/017-saga-deadlines tasks.md T020, T028). */
class DeadlineModelTest {

    private static Function<String, Optional<String>> properties(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key));
    }

    private static final Function<String, Optional<String>> NO_PROPERTIES = key -> Optional.empty();

    @Test
    @DisplayName("declarations by step, in declaration order, with fixed and context durations")
    void declarations() {
        DeadlineModel model = DeadlineModel.of(PaymentWindowSaga.class, NO_PROPERTIES);

        assertThat(model.isEmpty()).isFalse();
        assertThat(model.startedBy(PaymentWindowSaga.RESERVED)).extracting(DeadlineModel.Declaration::name)
                .containsExactly(PaymentWindowSaga.WINDOW, PaymentWindowSaga.REMINDER);
        DeadlineModel.Declaration window = model.startedBy(PaymentWindowSaga.RESERVED).get(0);
        assertThat(window.fixed()).isEmpty();
        assertThat(window.durationFrom()).isEqualTo("paymentWindow");
        assertThat(window.cancelledBy()).containsExactly(PaymentWindowSaga.DECLINED);
        assertThat(model.startedBy(PaymentWindowSaga.REMIND)).singleElement()
                .satisfies(reminder -> assertThat(reminder.fixed()).contains(Duration.ofMinutes(10)));
        assertThat(model.startedBy(PaymentWindowSaga.PLACED)).isEmpty();
    }

    @Test
    @DisplayName("cancelling steps and timeout steps by deadline name")
    void cancellingAndTimeoutSteps() {
        DeadlineModel model = DeadlineModel.of(PaymentWindowSaga.class, NO_PROPERTIES);

        assertThat(model.cancelledBy(PaymentWindowSaga.DECLINED)).containsExactly(PaymentWindowSaga.WINDOW);
        assertThat(model.cancelledBy(PaymentWindowSaga.AUTHORIZED)).isEmpty();
        assertThat(model.expiryStep(PaymentWindowSaga.WINDOW)).contains(PaymentWindowSaga.EXPIRED);
        assertThat(model.expiryStep(PaymentWindowSaga.REMINDER)).contains(PaymentWindowSaga.REMIND);
        assertThat(model.expiryStep("unknown")).isEmpty();
    }

    @Test
    @DisplayName("a ${key:default} duration takes the property, else the default")
    void placeholder() {
        assertThat(DeadlineModel.of(PaymentWindowSaga.class, properties(Map.of("deadline.reminder", "PT3M")))
                .startedBy(PaymentWindowSaga.REMIND).get(0).fixed()).contains(Duration.ofMinutes(3));
        assertThat(DeadlineModel.placeholderErrors(PaymentWindowSaga.class, NO_PROPERTIES)).isEmpty();
    }

    @Test
    @DisplayName("an unparseable or negative property names saga, deadline, step and value")
    void unresolvable() {
        Function<String, Optional<String>> bad = properties(Map.of("deadline.reminder", "ten minutes"));

        assertThatThrownBy(() -> DeadlineModel.of(PaymentWindowSaga.class, bad))
                .isInstanceOf(DeadlineModel.UnresolvedDurationException.class)
                .hasMessage("@Saga class PaymentWindowSaga: deadline 'reminder' on step 'onStockReserved' has duration"
                        + " '${deadline.reminder:PT10M}', resolved to 'ten minutes', which is not an ISO-8601 duration");
        assertThat(DeadlineModel.placeholderErrors(PaymentWindowSaga.class,
                properties(Map.of("deadline.reminder", "-PT1M"))))
                .hasSize(2).allSatisfy(error -> assertThat(error).endsWith("which is negative"));
    }

    @Test
    @DisplayName("a saga without deadlines has the empty model")
    void none() {
        assertThat(DeadlineModel.of(ShipmentSaga.class, NO_PROPERTIES)).isSameAs(DeadlineModel.none());
        assertThat(DeadlineModel.none().isEmpty()).isTrue();
    }
}
