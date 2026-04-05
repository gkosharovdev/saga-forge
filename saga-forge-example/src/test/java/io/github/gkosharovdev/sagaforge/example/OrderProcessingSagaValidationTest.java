package io.github.gkosharovdev.sagaforge.example;

import io.github.gkosharovdev.sagaforge.core.scanner.ReflectiveAnnotationScanner;
import io.github.gkosharovdev.sagaforge.core.scanner.SagaValidationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that validates the {@link OrderProcessingSaga} example class
 * passes all annotation validation rules enforced by the {@link ReflectiveAnnotationScanner}.
 *
 * Validates: Requirements 12.9
 */
class OrderProcessingSagaValidationTest {

    private final ReflectiveAnnotationScanner scanner = new ReflectiveAnnotationScanner();

    @Test
    void orderProcessingSagaShouldPassValidation() {
        SagaValidationResult result = scanner.validate(OrderProcessingSaga.class);

        assertThat(result.isValid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }
}
