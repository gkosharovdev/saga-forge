package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
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
 * Rules R21 and R22 / Properties 31 and 32: compensation method shape and one compensation per step
 * (specs/006-saga-compensation FR-008 to FR-010, {@code .kiro/specs/saga-forge-framework/design.md}, tasks.md T020).
 */
class CompensationValidationTest {

    private final ReflectiveAnnotationScanner scanner = new ReflectiveAnnotationScanner();

    /** Two failure outcomes and three compensations, one taking the trigger: no error at all. */
    @Saga
    static class ValidCompensationSaga {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        public void start(TestEvent event) {
        }

        @SagaStep(associationProperty = "orderId")
        public void pay(TestEvent2 event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(Shipped event) {
        }

        @StartCompensation
        @SagaStep(associationProperty = "orderId")
        public void reject(TestEvent3 event) {
        }

        @StartCompensation
        @SagaStep(associationProperty = "orderId")
        public void outOfStock(OutOfStock event) {
        }

        @SagaCompensation(forStep = "start")
        public void undoStart() {
        }

        @SagaCompensation(forStep = "pay")
        public void refund(CompensationTrigger trigger) {
        }

        @SagaCompensation(forStep = "end")
        public void undoEnd() {
        }
    }

    @io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent(
            aggregate = io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestAggregate.class)
    record Shipped(@io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier String orderId) {
    }

    @io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent(
            aggregate = io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestAggregate.class)
    record OutOfStock(@io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier String orderId) {
    }

    @Saga
    static class BadShapeSaga {

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

        @SagaCompensation(forStep = "start")
        public String returnsValue() {
            return "x";
        }

        @SagaCompensation(forStep = "end")
        public void wrongParameter(String reason) {
        }
    }

    @Saga
    static class TooManyParametersSaga {

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

        @SagaCompensation(forStep = "start")
        public void twoParameters(CompensationTrigger trigger, String extra) {
        }
    }

    @Saga
    static class CompensationIsStepSaga {

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

        @SagaCompensation(forStep = "start")
        @SagaStep(associationProperty = "orderId")
        public void undo(TestEvent3 event) {
        }
    }

    @Saga
    static class DuplicateCompensationSaga {

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

        @SagaCompensation(forStep = "start")
        public void undoTwo() {
        }

        @SagaCompensation(forStep = "start")
        public void undoOne() {
        }
    }

    @Saga
    static class CompensatesTriggerStepSaga {

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

        @SagaCompensation(forStep = "reject")
        public void undoReject() {
        }
    }

    /** One fixture and the R21/R22 errors it must produce, in order. */
    enum Case {
        VALID(ValidCompensationSaga.class, List.of()),
        BAD_SHAPE(BadShapeSaga.class, List.of(
                "@SagaCompensation method returnsValue in BadShapeSaga must return void",
                "@SagaCompensation method wrongParameter in BadShapeSaga must declare no parameter or one "
                        + "CompensationTrigger parameter")),
        TOO_MANY_PARAMETERS(TooManyParametersSaga.class, List.of(
                "@SagaCompensation method twoParameters in TooManyParametersSaga must declare no parameter or one "
                        + "CompensationTrigger parameter")),
        IS_STEP(CompensationIsStepSaga.class, List.of(
                "@SagaCompensation method undo in CompensationIsStepSaga must declare no parameter or one "
                        + "CompensationTrigger parameter",
                "@SagaCompensation method undo in CompensationIsStepSaga cannot also be annotated with @SagaStep")),
        DUPLICATE(DuplicateCompensationSaga.class, List.of(
                "@SagaCompensation methods undoOne, undoTwo in DuplicateCompensationSaga both compensate step start")),
        TRIGGER_STEP(CompensatesTriggerStepSaga.class, List.of(
                "@SagaCompensation method undoReject in CompensatesTriggerStepSaga compensates step reject, which is "
                        + "a @StartCompensation step"));

        final Class<?> saga;
        final List<String> errors;

        Case(Class<?> saga, List<String> errors) {
            this.saga = saga;
            this.errors = errors;
        }
    }

    private List<String> compensationErrors(Class<?> saga) {
        return scanner.validate(saga).errors().stream()
                .filter(error -> error.startsWith("@SagaCompensation method"))
                .toList();
    }

    @Test
    @DisplayName("two failure outcomes and three compensations, one taking the trigger, produce no error")
    void validSagaHasNoError() {
        assertThat(scanner.validate(ValidCompensationSaga.class).errors()).isEmpty();
        assertThat(scanner.validateAll(List.of(ValidCompensationSaga.class)).allErrors()).isEmpty();
    }

    @Test
    @DisplayName("R21: a non-void return and a parameter other than one CompensationTrigger are reported")
    void shape() {
        assertThat(compensationErrors(BadShapeSaga.class)).containsExactlyElementsOf(Case.BAD_SHAPE.errors);
        assertThat(compensationErrors(TooManyParametersSaga.class))
                .containsExactlyElementsOf(Case.TOO_MANY_PARAMETERS.errors);
    }

    @Test
    @DisplayName("R21: a compensation that is also a @SagaStep is reported")
    void compensationIsStep() {
        assertThat(compensationErrors(CompensationIsStepSaga.class)).containsExactlyElementsOf(Case.IS_STEP.errors);
    }

    @Test
    @DisplayName("R22: two compensations for one step are reported once, with the method names sorted")
    void duplicate() {
        assertThat(compensationErrors(DuplicateCompensationSaga.class))
                .containsExactlyElementsOf(Case.DUPLICATE.errors);
    }

    @Test
    @DisplayName("R22: a compensation of a @StartCompensation step is reported")
    void compensatesTriggerStep() {
        assertThat(compensationErrors(CompensatesTriggerStepSaga.class))
                .containsExactlyElementsOf(Case.TRIGGER_STEP.errors);
    }

    @Test
    @DisplayName("validateAll reports the same R21 and R22 errors as validate")
    void validateAllAgrees() {
        assertThat(scanner.validateAll(List.of(BadShapeSaga.class, DuplicateCompensationSaga.class)).allErrors())
                .containsAll(Case.BAD_SHAPE.errors)
                .containsAll(Case.DUPLICATE.errors);
    }

    @Property(tries = 60)
    void compensationShape(@ForAll("cases") Case fixture) {
        assertThat(compensationErrors(fixture.saga)).containsExactlyElementsOf(fixture.errors);
    }

    @Provide
    Arbitrary<Case> cases() {
        return Arbitraries.of(Case.class);
    }
}
