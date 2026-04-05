package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

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
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link ReflectiveAnnotationScanner}.
 *
 * <p>Each property test uses jqwik {@code @ForAll} with {@code @Provide} arbitraries
 * that randomly select from pre-built fixture classes relevant to the property under test.
 * This ensures the scanner's validation rules hold across all fixture variants.</p>
 */
@Tag("Feature_saga-forge-framework")
class ReflectiveAnnotationScannerPropertyTest {

    private final ReflectiveAnnotationScanner scanner = new ReflectiveAnnotationScanner();

    // ── Property 1: SagaId cardinality enforcement ──────────────────────────

    /**
     * Property 1: For any @Saga class with wrong @SagaId count or type,
     * validation must fail with a @SagaId-related error.
     *
     * <p><b>Validates: Requirements 2.3, 2.4</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_1_SagaId_cardinality_enforcement")
    void sagaIdCardinalityEnforcement(@ForAll("invalidSagaIdClasses") Class<?> sagaClass) {
        SagaValidationResult result = scanner.validate(sagaClass);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("@SagaId"));
    }

    @Provide
    Arbitrary<Class<?>> invalidSagaIdClasses() {
        return net.jqwik.api.Arbitraries.of(
                NoSagaIdSaga.class,
                MultipleSagaIdSaga.class,
                NonStringSagaIdSaga.class
        );
    }

    // ── Property 2: Lifecycle method cardinality enforcement ────────────────

    /**
     * Property 2: For any @Saga class with wrong @StartSaga/@EndSaga counts,
     * validation must fail.
     *
     * <p><b>Validates: Requirements 3.3, 3.4</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_2_Lifecycle_method_cardinality_enforcement")
    void lifecycleMethodCardinalityEnforcement(@ForAll("invalidLifecycleCardinalityClasses") Class<?> sagaClass) {
        SagaValidationResult result = scanner.validate(sagaClass);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e ->
                e.contains("@StartSaga") || e.contains("@EndSaga"));
    }

    @Provide
    Arbitrary<Class<?>> invalidLifecycleCardinalityClasses() {
        return net.jqwik.api.Arbitraries.of(
                NoStartSagaSaga.class,
                MultipleStartSagaSaga.class,
                NoEndSagaSaga.class
        );
    }

    // ── Property 3: Lifecycle annotations require @SagaStep co-occurrence ──

    /**
     * Property 3: For any lifecycle method without @SagaStep co-annotation,
     * validation must fail with a co-occurrence error.
     *
     * <p><b>Validates: Requirements 3.5, 3.6, 3.7</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_3_Lifecycle_SagaStep_co_occurrence")
    void lifecycleRequiresSagaStepCoOccurrence(@ForAll("lifecycleWithoutStepClasses") Class<?> sagaClass) {
        SagaValidationResult result = scanner.validate(sagaClass);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("but missing @SagaStep"));
    }

    @Provide
    Arbitrary<Class<?>> lifecycleWithoutStepClasses() {
        return net.jqwik.api.Arbitraries.of(
                StartSagaWithoutStepSaga.class,
                EndSagaWithoutStepSaga.class
        );
    }

    // ── Property 4: SagaStep parameter validation ───────────────────────────

    /**
     * Property 4: For any @SagaStep method with wrong param count or
     * non-@DomainEvent param, validation must fail with a parameter error.
     *
     * <p><b>Validates: Requirements 4.4, 4.5, 4.6</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_4_SagaStep_parameter_validation")
    void sagaStepParameterValidation(@ForAll("invalidStepParamClasses") Class<?> sagaClass) {
        SagaValidationResult result = scanner.validate(sagaClass);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e ->
                e.contains("must accept exactly one parameter") || e.contains("must be annotated with @DomainEvent"));
    }

    @Provide
    Arbitrary<Class<?>> invalidStepParamClasses() {
        return net.jqwik.api.Arbitraries.of(
                StepWithNoParamSaga.class,
                StepWithMultipleParamsSaga.class,
                StepWithNonDomainEventParamSaga.class
        );
    }

    // ── Property 5: SagaCompensation forStep reference validity ─────────────

    /**
     * Property 5: For any @SagaCompensation with invalid forStep reference,
     * validation must fail with a forStep error.
     *
     * <p><b>Validates: Requirements 5.4, 5.5</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_5_SagaCompensation_forStep_reference_validity")
    void compensationForStepReferenceValidity(@ForAll("invalidCompensationClasses") Class<?> sagaClass) {
        SagaValidationResult result = scanner.validate(sagaClass);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("forStep"));
    }

    @Provide
    Arbitrary<Class<?>> invalidCompensationClasses() {
        return net.jqwik.api.Arbitraries.of(
                CompensationInvalidForStepSaga.class,
                CompensationForNonStepMethodSaga.class
        );
    }

    // ── Property 6: Valid saga classes pass validation ───────────────────────

    /**
     * Property 6: For any structurally valid saga class, validation must pass
     * with no errors.
     *
     * <p><b>Validates: Requirements 1.3, 2.3, 3.3, 3.4, 3.5, 3.6, 4.4, 5.4, 5.5</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_6_Valid_saga_classes_pass_validation")
    void validSagaClassesPassValidation(@ForAll("validSagaClasses") Class<?> sagaClass) {
        SagaValidationResult result = scanner.validate(sagaClass);

        assertThat(result.isValid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Provide
    Arbitrary<Class<?>> validSagaClasses() {
        return net.jqwik.api.Arbitraries.of(ValidSaga.class);
    }

    // ── Property 7: Non-concrete saga classes are rejected ──────────────────

    /**
     * Property 7: For any interface or abstract class annotated with @Saga,
     * validation must fail with a concrete-class error.
     *
     * <p><b>Validates: Requirements 1.4</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_7_Non_concrete_saga_classes_rejected")
    void nonConcreteSagaClassesRejected(@ForAll("nonConcreteClasses") Class<?> sagaClass) {
        SagaValidationResult result = scanner.validate(sagaClass);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("must be a concrete class"));
    }

    @Provide
    Arbitrary<Class<?>> nonConcreteClasses() {
        return net.jqwik.api.Arbitraries.of(
                InterfaceSaga.class,
                AbstractSagaClass.class
        );
    }
}
