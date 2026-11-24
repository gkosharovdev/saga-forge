package io.github.gkosharovdev.sagaforge.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("Admission and the shared instance checks")
class AdmissionTest {

    private static final Instant AT = Instant.parse("2026-11-23T10:00:00Z");

    /** A valid instance without compensation in {@code status}, with the outcome or failure its status requires. */
    private static SagaInstance instance(SagaStatus status, Map<String, StepRecord> steps) {
        return new SagaInstance("saga-1", "OrderSaga", Map.of(), Map.of("orderId", "o-1"), status, AT, AT, steps,
                status == SagaStatus.COMPLETED ? "end" : null,
                status == SagaStatus.FAILED ? SagaFailure.of("start", new IllegalStateException("boom"), AT) : null);
    }

    /** What a caller does with a typed reason: an exhaustive switch, no cast. */
    private static String describe(Admission<Integer> admission) {
        return switch (admission) {
            case Admission.Proceed<Integer> proceed -> "proceed";
            case Admission.Skip<Integer> skip -> "skip " + (skip.reason() + 1);
        };
    }

    @Nested
    @DisplayName("Admission")
    class Decisions {

        @Test
        @DisplayName("a skip carries its typed reason to an exhaustive switch")
        void skipCarriesTypedReason() {
            assertThat(describe(Admission.skip(41))).isEqualTo("skip 42");
            assertThat(describe(Admission.proceed())).isEqualTo("proceed");
        }

        @Test
        @DisplayName("a skip without a reason is rejected")
        void skipRequiresReason() {
            assertThatNullPointerException().isThrownBy(() -> Admission.skip(null)).withMessage("reason");
        }

        @Test
        @DisplayName("proceed values are equal whatever their type argument")
        void proceedValuesAreEqual() {
            assertThat(Admission.<String>proceed()).isEqualTo(Admission.<Integer>proceed());
        }
    }

    @Nested
    @DisplayName("InstanceChecks")
    class Checks {

        @Test
        @DisplayName("a missing instance is missing or terminal")
        void missing() {
            assertThat(InstanceChecks.missingOrTerminal(Optional.empty())).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = SagaStatus.class, names = {"COMPLETED", "FAILED"})
        @DisplayName("a terminal instance is missing or terminal")
        void terminal(SagaStatus status) {
            assertThat(InstanceChecks.missingOrTerminal(Optional.of(instance(status, Map.of())))).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = SagaStatus.class, names = {"CREATED", "RUNNING"})
        @DisplayName("a live instance is neither")
        void live(SagaStatus status) {
            assertThat(InstanceChecks.missingOrTerminal(Optional.of(instance(status, Map.of())))).isFalse();
        }

        @Test
        @DisplayName("an instance with a completed step has committed")
        void anyStepCompleted() {
            SagaInstance committed = instance(SagaStatus.RUNNING, Map.of(
                    "start", new StepRecord(StepStatus.COMPLETED, List.of()),
                    "next", StepRecord.notRun()));
            SagaInstance nothing = instance(SagaStatus.RUNNING, Map.of("start", StepRecord.notRun()));

            assertThat(InstanceChecks.anyStepCompleted(committed)).isTrue();
            assertThat(InstanceChecks.anyStepCompleted(nothing)).isFalse();
        }
    }
}
