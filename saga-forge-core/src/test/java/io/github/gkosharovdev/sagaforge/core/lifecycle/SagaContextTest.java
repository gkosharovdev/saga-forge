package io.github.gkosharovdev.sagaforge.core.lifecycle;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.entry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.gkosharovdev.sagaforge.core.lifecycle.fixtures.InMemorySagaContext;

/**
 * Contract-behavior tests for {@link SagaContext} (spec User Story 1), exercised through the
 * test-only {@link InMemorySagaContext} double.
 */
class SagaContextTest {

    private SagaContext context;

    @BeforeEach
    void setUp() {
        context = new InMemorySagaContext();
    }

    @Nested
    @DisplayName("putState then getState (scenario 1, FR-001/FR-002)")
    class WriteThenRead {

        @Test
        @DisplayName("a value written by one step is read back unchanged by a later step")
        void roundTripReturnsSameValue() {
            BigDecimal amount = new BigDecimal("42.50");

            // step 1
            context.putState("paymentAmount", amount);
            // step 2
            Optional<BigDecimal> read = context.getState("paymentAmount", BigDecimal.class);

            assertThat(read).containsSame(amount);
        }

        @Test
        @DisplayName("a value can be read back as a supertype it is assignable to")
        void readAsAssignableSupertype() {
            context.putState("orderId", "order-1");

            assertThat(context.getState("orderId", CharSequence.class)).contains("order-1");
            assertThat(context.getState("orderId", Object.class)).contains("order-1");
        }

        @Test
        @DisplayName("putState under an existing name wholesale-replaces the previous value")
        void putStateReplacesPreviousValue() {
            context.putState("status", "PENDING");
            context.putState("status", "PAID");

            assertThat(context.getState("status", String.class)).contains("PAID");
        }
    }

    @Nested
    @DisplayName("getState on a name never written (scenario 2, FR-004)")
    class AbsentValue {

        @Test
        @DisplayName("returns Optional.empty() for a name never written")
        void neverWrittenReturnsEmpty() {
            assertThat(context.getState("missing", String.class)).isEmpty();
        }

        @Test
        @DisplayName("returns Optional.empty() for an unwritten name even when other names are set")
        void otherNamesDoNotLeak() {
            context.putState("present", "value");

            assertThat(context.getState("missing", String.class)).isEmpty();
        }
    }

    @Nested
    @DisplayName("getState with an incompatible type (scenario 3, FR-003)")
    class TypeMismatch {

        @Test
        @DisplayName("throws SagaStateTypeMismatchException carrying state name, expected and actual types")
        void incompatibleTypeThrowsMismatch() {
            context.putState("paymentAmount", new BigDecimal("10.00"));

            SagaStateTypeMismatchException ex = catchThrowableOfType(
                    SagaStateTypeMismatchException.class,
                    () -> context.getState("paymentAmount", String.class));

            assertThat(ex).isNotNull();
            assertThat(ex.getStateName()).isEqualTo("paymentAmount");
            assertThat(ex.getExpectedType()).isEqualTo(String.class);
            assertThat(ex.getActualType()).isEqualTo(BigDecimal.class);
        }

        @Test
        @DisplayName("is not a ClassCastException")
        void mismatchIsNotClassCastException() {
            context.putState("count", 3);

            assertThatThrownBy(() -> context.getState("count", String.class))
                    .isInstanceOf(SagaStateTypeMismatchException.class)
                    .isNotInstanceOf(ClassCastException.class);
        }

        @Test
        @DisplayName("leaves the stored value readable with its correct type")
        void mismatchDoesNotAlterStoredValue() {
            context.putState("count", 3);

            assertThatThrownBy(() -> context.getState("count", String.class))
                    .isInstanceOf(SagaStateTypeMismatchException.class);
            assertThat(context.getState("count", Integer.class)).contains(3);
        }
    }

    @Nested
    @DisplayName("updateStateAttribute field-level updates (scenarios 5-7, FR-002a/b/c)")
    class FieldLevelUpdate {

        @Test
        @DisplayName("two updates to different fields of the same name leave both fields and unrelated fields present (SC-005)")
        void successiveFieldUpdatesMerge() {
            Map<String, Object> initial = new LinkedHashMap<>();
            initial.put("customerId", "customer-7");
            context.putState("order", initial);

            // step 1
            context.updateStateAttribute("order", "paymentId", "payment-1");
            // step 2
            context.updateStateAttribute("order", "shipmentId", "shipment-1");

            assertThat(context.getState("order", Map.class)).hasValueSatisfying(order -> assertThat(order)
                    .containsOnly(
                            entry("customerId", "customer-7"),
                            entry("paymentId", "payment-1"),
                            entry("shipmentId", "shipment-1")));
        }

        @Test
        @DisplayName("updating the same field twice replaces only that field")
        void sameFieldUpdateReplacesOnlyThatField() {
            context.updateStateAttribute("order", "status", "PENDING");
            context.updateStateAttribute("order", "paymentId", "payment-1");
            context.updateStateAttribute("order", "status", "PAID");

            assertThat(context.getState("order", Map.class)).hasValueSatisfying(order -> assertThat(order)
                    .containsOnly(entry("status", "PAID"), entry("paymentId", "payment-1")));
        }

        @Test
        @DisplayName("on a name with no existing value creates a structured value with just that field (FR-002b)")
        void updateOnAbsentNameCreatesStructuredValue() {
            context.updateStateAttribute("order", "paymentId", "payment-1");

            assertThat(context.getState("order", Map.class)).hasValueSatisfying(order -> assertThat(order)
                    .containsOnly(entry("paymentId", "payment-1")));
        }

        @Test
        @DisplayName("works on a value stored from an immutable map")
        void updateOnImmutableMapSource() {
            context.putState("order", Map.of("customerId", "customer-7"));

            context.updateStateAttribute("order", "paymentId", "payment-1");

            assertThat(context.getState("order", Map.class)).hasValueSatisfying(order -> assertThat(order)
                    .containsOnly(entry("customerId", "customer-7"), entry("paymentId", "payment-1")));
        }

        @Test
        @DisplayName("does not mutate the caller's original map passed to putState")
        void updateDoesNotMutateCallersMap() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("customerId", "customer-7");
            context.putState("order", original);

            context.updateStateAttribute("order", "paymentId", "payment-1");

            assertThat(original).containsOnly(entry("customerId", "customer-7"));
        }

        @Test
        @DisplayName("on a scalar value throws NonStructuredStateValueException carrying name, field and actual type (FR-002c)")
        void updateOnScalarThrows() {
            context.putState("paymentAmount", new BigDecimal("10.00"));

            NonStructuredStateValueException ex = catchThrowableOfType(
                    NonStructuredStateValueException.class,
                    () -> context.updateStateAttribute("paymentAmount", "currency", "EUR"));

            assertThat(ex).isNotNull();
            assertThat(ex.getStateName()).isEqualTo("paymentAmount");
            assertThat(ex.getFieldName()).isEqualTo("currency");
            assertThat(ex.getActualType()).isEqualTo(BigDecimal.class);
        }

        @Test
        @DisplayName("on a scalar value leaves the existing scalar unchanged")
        void updateOnScalarDoesNotMutate() {
            BigDecimal amount = new BigDecimal("10.00");
            context.putState("paymentAmount", amount);

            assertThatThrownBy(() -> context.updateStateAttribute("paymentAmount", "currency", "EUR"))
                    .isInstanceOf(NonStructuredStateValueException.class);
            assertThat(context.getState("paymentAmount", BigDecimal.class)).containsSame(amount);
        }
    }

    @Nested
    @DisplayName("argument validation for getState/putState/updateStateAttribute")
    class ArgumentValidation {

        @ParameterizedTest(name = "name = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n"})
        @DisplayName("getState rejects a null or blank name")
        void getStateRejectsNullOrBlankName(String name) {
            assertThatThrownBy(() -> context.getState(name, String.class))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("getState rejects a null type")
        void getStateRejectsNullType() {
            assertThatThrownBy(() -> context.getState("name", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "name = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n"})
        @DisplayName("putState rejects a null or blank name")
        void putStateRejectsNullOrBlankName(String name) {
            assertThatThrownBy(() -> context.putState(name, "value"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("putState rejects a null value and stores nothing")
        void putStateRejectsNullValue() {
            assertThatThrownBy(() -> context.putState("name", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(context.getState("name", Object.class)).isEmpty();
        }

        @Test
        @DisplayName("putState with a null value does not clear a previously stored value")
        void putStateNullDoesNotClearExisting() {
            context.putState("name", "original");

            assertThatThrownBy(() -> context.putState("name", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(context.getState("name", String.class)).contains("original");
        }

        @ParameterizedTest(name = "name = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n"})
        @DisplayName("updateStateAttribute rejects a null or blank name")
        void updateStateAttributeRejectsNullOrBlankName(String name) {
            assertThatThrownBy(() -> context.updateStateAttribute(name, "field", "value"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "field = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n"})
        @DisplayName("updateStateAttribute rejects a null or blank field and stores nothing")
        void updateStateAttributeRejectsNullOrBlankField(String field) {
            assertThatThrownBy(() -> context.updateStateAttribute("name", field, "value"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(context.getState("name", Object.class)).isEmpty();
        }

        @Test
        @DisplayName("updateStateAttribute rejects a null value and leaves the existing structured value unchanged")
        void updateStateAttributeRejectsNullValue() {
            context.updateStateAttribute("name", "field", "original");

            assertThatThrownBy(() -> context.updateStateAttribute("name", "field", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(context.getState("name", Map.class)).hasValueSatisfying(value -> assertThat(value)
                    .containsOnly(entry("field", "original")));
        }
    }
}
