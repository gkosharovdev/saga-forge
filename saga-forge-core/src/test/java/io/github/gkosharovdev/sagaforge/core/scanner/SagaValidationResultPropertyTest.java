package io.github.gkosharovdev.sagaforge.core.scanner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.Size;

/**
 * Property 8: SagaValidationResult immutability
 *
 * For any SagaValidationResult instance, the errors list shall be unmodifiable.
 * Attempting to mutate the list (add, remove, clear) shall throw
 * UnsupportedOperationException. This holds regardless of the input list
 * passed to the constructor.
 *
 * <p><b>Validates: Requirements 1.3, 2.3</b></p>
 */
@Tag("Feature_saga-forge-framework")
@Tag("Property_8_SagaValidationResult_immutability")
class SagaValidationResultPropertyTest {

    @Property(tries = 100)
    void errorsListRejectsAdd(@ForAll @Size(max = 20) List<String> errors) {
        var result = new SagaValidationResult(Object.class, new ArrayList<>(errors));

        assertThatThrownBy(() -> result.errors().add("injected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Property(tries = 100)
    void errorsListRejectsRemove(@ForAll @Size(min = 1, max = 20) List<String> errors) {
        var result = new SagaValidationResult(Object.class, new ArrayList<>(errors));

        assertThatThrownBy(() -> result.errors().remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Property(tries = 100)
    void errorsListRejectsClear(@ForAll @Size(max = 20) List<String> errors) {
        var result = new SagaValidationResult(Object.class, new ArrayList<>(errors));

        assertThatThrownBy(() -> result.errors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
