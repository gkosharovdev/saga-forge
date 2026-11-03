package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.NoSagaIdSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.SagaWithContextFieldSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.ValidSaga;

class ValidationReportTest {

    private static final String NO_SAGA_ID_ERROR =
            "@Saga class NoSagaIdSaga must declare exactly one @SagaId field, found 0";

    @Nested
    @DisplayName("ValidationReport record invariants")
    class RecordInvariants {

        @Test
        @DisplayName("null classResults is rejected")
        void nullClassResultsRejected() {
            assertThatThrownBy(() -> new ValidationReport(null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null modelErrors is rejected")
        void nullModelErrorsRejected() {
            assertThatThrownBy(() -> new ValidationReport(List.of(), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null elements are rejected")
        void nullElementsRejected() {
            assertThatThrownBy(() -> new ValidationReport(Arrays.asList((SagaValidationResult) null), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ValidationReport(List.of(), Arrays.asList((String) null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("lists are defensively copied and unmodifiable")
        void defensiveCopies() {
            List<SagaValidationResult> results = new ArrayList<>();
            results.add(new SagaValidationResult(ValidSaga.class, List.of()));
            List<String> modelErrors = new ArrayList<>();

            ValidationReport report = new ValidationReport(results, modelErrors);
            results.add(new SagaValidationResult(NoSagaIdSaga.class, List.of(NO_SAGA_ID_ERROR)));
            modelErrors.add("late error");

            assertThat(report.classResults()).hasSize(1);
            assertThat(report.modelErrors()).isEmpty();
            assertThat(report.isValid()).isTrue();
            assertThatThrownBy(() -> report.classResults().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> report.modelErrors().add("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("isValid is false when any class result is invalid")
        void invalidClassResultMakesReportInvalid() {
            ValidationReport report = new ValidationReport(List.of(
                    new SagaValidationResult(ValidSaga.class, List.of()),
                    new SagaValidationResult(NoSagaIdSaga.class, List.of(NO_SAGA_ID_ERROR))), List.of());

            assertThat(report.isValid()).isFalse();
            assertThat(report.allErrors()).containsExactly(NO_SAGA_ID_ERROR);
        }

        @Test
        @DisplayName("isValid is false when there is a model error")
        void modelErrorMakesReportInvalid() {
            ValidationReport report = new ValidationReport(
                    List.of(new SagaValidationResult(ValidSaga.class, List.of())), List.of("model error"));

            assertThat(report.isValid()).isFalse();
            assertThat(report.allErrors()).containsExactly("model error");
        }

        @Test
        @DisplayName("allErrors lists class errors before model errors, messages unchanged")
        void allErrorsOrder() {
            ValidationReport report = new ValidationReport(
                    List.of(new SagaValidationResult(NoSagaIdSaga.class, List.of(NO_SAGA_ID_ERROR))),
                    List.of("model error"));

            assertThat(report.allErrors()).containsExactly(NO_SAGA_ID_ERROR, "model error");
        }
    }

    @Nested
    @DisplayName("ReflectiveAnnotationScanner.validateAll")
    class ValidateAll {

        private final ReflectiveAnnotationScanner scanner = new ReflectiveAnnotationScanner();

        @Test
        @DisplayName("a model of valid sagas is valid")
        void validModel() {
            ValidationReport report = scanner.validateAll(List.of(ValidSaga.class, SagaWithContextFieldSaga.class));

            assertThat(report.isValid()).isTrue();
            assertThat(report.classResults())
                    .extracting(SagaValidationResult::sagaClass)
                    .containsExactly(ValidSaga.class, SagaWithContextFieldSaga.class);
            assertThat(report.modelErrors()).isEmpty();
        }

        @Test
        @DisplayName("an invalid saga makes the report invalid and keeps one result per saga class")
        void invalidModel() {
            ValidationReport report = scanner.validateAll(List.of(ValidSaga.class, NoSagaIdSaga.class));

            assertThat(report.isValid()).isFalse();
            assertThat(report.classResults())
                    .extracting(SagaValidationResult::sagaClass)
                    .containsExactly(ValidSaga.class, NoSagaIdSaga.class);
            assertThat(report.classResults().get(0).isValid()).isTrue();
            assertThat(report.classResults().get(1).errors()).contains(NO_SAGA_ID_ERROR);
            assertThat(report.allErrors()).contains(NO_SAGA_ID_ERROR);
        }

        @Test
        @DisplayName("class results match validate(Class) for each saga")
        void matchesSingleClassValidation() {
            ValidationReport report = scanner.validateAll(List.of(NoSagaIdSaga.class));

            assertThat(report.classResults()).containsExactly(scanner.validate(NoSagaIdSaga.class));
        }

        @Test
        @DisplayName("non-@Saga classes produce no result")
        void nonSagaClassesIgnored() {
            ValidationReport report = scanner.validateAll(List.of(TestEvent.class, ValidSaga.class));

            assertThat(report.classResults())
                    .extracting(SagaValidationResult::sagaClass)
                    .containsExactly(ValidSaga.class);
            assertThat(scanner.validateAll(List.of(TestEvent.class)).classResults()).isEmpty();
        }

        @Test
        @DisplayName("an empty model is valid")
        void emptyModel() {
            ValidationReport report = scanner.validateAll(List.of());

            assertThat(report.isValid()).isTrue();
            assertThat(report.classResults()).isEmpty();
        }

        @Test
        @DisplayName("null collection is rejected")
        void nullCollectionRejected() {
            assertThatThrownBy(() -> scanner.validateAll(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null element is rejected")
        void nullElementRejected() {
            Collection<Class<?>> classes = Arrays.asList(ValidSaga.class, null);

            assertThatThrownBy(() -> scanner.validateAll(classes))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
