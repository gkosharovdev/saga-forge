package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent2;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent3;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rule R20 / Property 30: {@code @StartCompensation} placement (specs/006-saga-compensation FR-001a,
 * {@code .kiro/specs/saga-forge-framework/design.md}, tasks.md T020).
 */
class StartCompensationValidationTest {

    private final ReflectiveAnnotationScanner scanner = new ReflectiveAnnotationScanner();

    @Saga
    static class ValidFailureOutcomeSaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent2 event) {
        }

        @StartCompensation
        @SagaStep(associationProperty = "orderId")
        public void reject(TestEvent3 event) {
        }
    }

    @Saga
    static class MissingSagaStepSaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent2 event) {
        }

        @StartCompensation
        public void reject(TestEvent3 event) {
        }
    }

    @Saga
    static class WithEndSagaSaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        public void start(TestEvent event) {
        }

        @EndSaga
        @StartCompensation
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent2 event) {
        }
    }

    @Saga
    static class WithStartSagaSaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @StartCompensation
        @SagaStep(associationProperty = "orderId")
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent2 event) {
        }
    }

    @Saga
    static class EveryViolationSaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent2 event) {
        }

        @EndSaga
        @StartCompensation
        public void reject(TestEvent3 event) {
        }
    }

    /** One fixture and the R20 errors it must produce, in order. */
    enum Case {
        VALID(ValidFailureOutcomeSaga.class, List.of()),
        MISSING_STEP(MissingSagaStepSaga.class, List.of(
                "Method reject in MissingSagaStepSaga is annotated with @StartCompensation but missing @SagaStep")),
        WITH_END(WithEndSagaSaga.class, List.of(
                "@StartCompensation method end in WithEndSagaSaga cannot also be annotated with @EndSaga")),
        WITH_START(WithStartSagaSaga.class, List.of(
                "@StartCompensation method start in WithStartSagaSaga cannot also be annotated with @StartSaga")),
        EVERY_VIOLATION(EveryViolationSaga.class, List.of(
                "Method reject in EveryViolationSaga is annotated with @StartCompensation but missing @SagaStep",
                "@StartCompensation method reject in EveryViolationSaga cannot also be annotated with @EndSaga"));

        final Class<?> saga;
        final List<String> errors;

        Case(Class<?> saga, List<String> errors) {
            this.saga = saga;
            this.errors = errors;
        }
    }

    private List<String> r20Errors(Class<?> saga) {
        return scanner.validate(saga).errors().stream()
                .filter(error -> error.contains("@StartCompensation"))
                .toList();
    }

    @Test
    @DisplayName("a @StartCompensation step with @SagaStep alone is valid: no error at all")
    void validSagaHasNoError() {
        assertThat(scanner.validate(ValidFailureOutcomeSaga.class).errors()).isEmpty();
    }

    @Test
    @DisplayName("a missing @SagaStep is reported with the R4 wording")
    void missingSagaStep() {
        assertThat(r20Errors(MissingSagaStepSaga.class)).containsExactlyElementsOf(Case.MISSING_STEP.errors);
    }

    @Test
    @DisplayName("@StartCompensation together with @EndSaga or @StartSaga is reported")
    void withOtherLifecycleAnnotation() {
        assertThat(r20Errors(WithEndSagaSaga.class)).containsExactlyElementsOf(Case.WITH_END.errors);
        assertThat(r20Errors(WithStartSagaSaga.class)).containsExactlyElementsOf(Case.WITH_START.errors);
    }

    @Test
    @DisplayName("validateAll reports the same R20 errors as validate")
    void validateAllAgrees() {
        ValidationReport report = scanner.validateAll(List.of(EveryViolationSaga.class));
        assertThat(report.allErrors()).containsAll(Case.EVERY_VIOLATION.errors);
    }

    @Property(tries = 50)
    void startCompensationPlacement(@ForAll("cases") Case fixture) {
        assertThat(r20Errors(fixture.saga)).containsExactlyElementsOf(fixture.errors);
    }

    @Provide
    Arbitrary<Case> cases() {
        return Arbitraries.of(Case.class);
    }
}
