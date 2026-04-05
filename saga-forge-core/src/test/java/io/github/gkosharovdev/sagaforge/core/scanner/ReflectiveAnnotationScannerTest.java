package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.AbstractSagaClass;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.CompensationForNonStepMethodSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.CompensationInvalidForStepSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.EndSagaWithoutStepSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.InterfaceSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.MultipleSagaIdSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.MultipleStartSagaSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.NoEndSagaSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.NoSagaIdSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.NoStartSagaSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.NonStringSagaIdSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.StartSagaWithoutStepSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.StepWithMultipleParamsSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.StepWithNoParamSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.StepWithNonDomainEventParamSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.ValidSaga;

class ReflectiveAnnotationScannerTest {

    private ReflectiveAnnotationScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new ReflectiveAnnotationScanner();
    }

    @Test
    @DisplayName("null input throws IllegalArgumentException")
    void nullInputThrowsException() {
        assertThatThrownBy(() -> scanner.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("valid saga passes validation with no errors")
    void validSagaPassesValidation() {
        SagaValidationResult result = scanner.validate(ValidSaga.class);

        assertThat(result.isValid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.sagaClass()).isEqualTo(ValidSaga.class);
    }

    @Nested
    @DisplayName("Non-concrete class violations")
    class NonConcreteClassTests {

        @Test
        @DisplayName("abstract class is rejected")
        void abstractClassIsRejected() {
            SagaValidationResult result = scanner.validate(AbstractSagaClass.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@Saga class AbstractSagaClass must be a concrete class, not an interface or abstract class"));
        }

        @Test
        @DisplayName("interface is rejected")
        void interfaceIsRejected() {
            SagaValidationResult result = scanner.validate(InterfaceSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@Saga class InterfaceSaga must be a concrete class, not an interface or abstract class"));
        }
    }

    @Nested
    @DisplayName("@SagaId violations")
    class SagaIdTests {

        @Test
        @DisplayName("missing @SagaId produces error")
        void missingSagaId() {
            SagaValidationResult result = scanner.validate(NoSagaIdSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@Saga class NoSagaIdSaga must declare exactly one @SagaId field, found 0"));
        }

        @Test
        @DisplayName("multiple @SagaId fields produces error")
        void multipleSagaId() {
            SagaValidationResult result = scanner.validate(MultipleSagaIdSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@Saga class MultipleSagaIdSaga must declare exactly one @SagaId field, found 2"));
        }

        @Test
        @DisplayName("non-String @SagaId field produces error")
        void nonStringSagaId() {
            SagaValidationResult result = scanner.validate(NonStringSagaIdSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@SagaId field sagaId in NonStringSagaIdSaga must be of type String"));
        }
    }

    @Nested
    @DisplayName("Lifecycle cardinality violations")
    class LifecycleCardinalityTests {

        @Test
        @DisplayName("missing @StartSaga produces error")
        void missingStartSaga() {
            SagaValidationResult result = scanner.validate(NoStartSagaSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@Saga class NoStartSagaSaga must declare exactly one @StartSaga method, found 0"));
        }

        @Test
        @DisplayName("multiple @StartSaga produces error")
        void multipleStartSaga() {
            SagaValidationResult result = scanner.validate(MultipleStartSagaSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@Saga class MultipleStartSagaSaga must declare exactly one @StartSaga method, found 2"));
        }

        @Test
        @DisplayName("missing @EndSaga produces error")
        void missingEndSaga() {
            SagaValidationResult result = scanner.validate(NoEndSagaSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@Saga class NoEndSagaSaga must declare at least one @EndSaga method, found 0"));
        }
    }

    @Nested
    @DisplayName("Lifecycle co-occurrence violations")
    class LifecycleCoOccurrenceTests {

        @Test
        @DisplayName("@StartSaga without @SagaStep produces error")
        void startSagaWithoutStep() {
            SagaValidationResult result = scanner.validate(StartSagaWithoutStepSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("Method handleStart in StartSagaWithoutStepSaga is annotated with @StartSaga but missing @SagaStep"));
        }

        @Test
        @DisplayName("@EndSaga without @SagaStep produces error")
        void endSagaWithoutStep() {
            SagaValidationResult result = scanner.validate(EndSagaWithoutStepSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("Method handleEnd in EndSagaWithoutStepSaga is annotated with @EndSaga but missing @SagaStep"));
        }
    }

    @Nested
    @DisplayName("@SagaStep parameter violations")
    class SagaStepParameterTests {

        @Test
        @DisplayName("@SagaStep with no parameters produces error")
        void stepWithNoParam() {
            SagaValidationResult result = scanner.validate(StepWithNoParamSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@SagaStep method handleStart in StepWithNoParamSaga must accept exactly one parameter, found 0"));
        }

        @Test
        @DisplayName("@SagaStep with multiple parameters produces error")
        void stepWithMultipleParams() {
            SagaValidationResult result = scanner.validate(StepWithMultipleParamsSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@SagaStep method handleStart in StepWithMultipleParamsSaga must accept exactly one parameter, found 2"));
        }

        @Test
        @DisplayName("@SagaStep with non-@DomainEvent parameter produces error")
        void stepWithNonDomainEventParam() {
            SagaValidationResult result = scanner.validate(StepWithNonDomainEventParamSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@SagaStep method handleStart parameter type NotADomainEvent must be annotated with @DomainEvent"));
        }
    }

    @Nested
    @DisplayName("@SagaCompensation violations")
    class CompensationTests {

        @Test
        @DisplayName("forStep referencing non-existent method produces error")
        void compensationInvalidForStep() {
            SagaValidationResult result = scanner.validate(CompensationInvalidForStepSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@SagaCompensation on compensate: forStep 'nonExistentMethod' does not reference an existing method in CompensationInvalidForStepSaga"));
        }

        @Test
        @DisplayName("forStep referencing non-@SagaStep method produces error")
        void compensationForNonStepMethod() {
            SagaValidationResult result = scanner.validate(CompensationForNonStepMethodSaga.class);

            assertThat(result.isValid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                    e.equals("@SagaCompensation on compensate: forStep 'regularMethod' must reference a @SagaStep method"));
        }
    }

    @Test
    @DisplayName("all errors are collected in a single pass for InterfaceSaga")
    void allErrorsCollectedInSinglePass() {
        // InterfaceSaga is an interface with no fields or methods — it should trigger
        // multiple validation errors: non-concrete, missing @SagaId, missing @StartSaga, missing @EndSaga
        SagaValidationResult result = scanner.validate(InterfaceSaga.class);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(result.errors()).anyMatch(e -> e.contains("must be a concrete class"));
        assertThat(result.errors()).anyMatch(e -> e.contains("must declare exactly one @SagaId field, found 0"));
        assertThat(result.errors()).anyMatch(e -> e.contains("must declare exactly one @StartSaga method, found 0"));
        assertThat(result.errors()).anyMatch(e -> e.contains("must declare at least one @EndSaga method, found 0"));
    }
}
