package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies annotation metadata (target, retention, attributes) for all 13
 * Saga Forge annotations via reflection. The aggregate and command-handler
 * annotations are additionally checked for {@code @Documented}.
 */
class AnnotationMetadataTest {

    // --- @Saga ---

    @Nested
    @DisplayName("@Saga")
    class SagaAnnotationTest {

        @Test
        void targetIsType() {
            Target target = Saga.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.TYPE);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = Saga.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(Saga.class.getDeclaredMethods()).isEmpty();
        }
    }

    // --- @SagaId ---

    @Nested
    @DisplayName("@SagaId")
    class SagaIdAnnotationTest {

        @Test
        void targetIsField() {
            Target target = SagaId.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.FIELD);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = SagaId.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(SagaId.class.getDeclaredMethods()).isEmpty();
        }
    }

    // --- @StartSaga ---

    @Nested
    @DisplayName("@StartSaga")
    class StartSagaAnnotationTest {

        @Test
        void targetIsMethod() {
            Target target = StartSaga.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.METHOD);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = StartSaga.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(StartSaga.class.getDeclaredMethods()).isEmpty();
        }
    }

    // --- @EndSaga ---

    @Nested
    @DisplayName("@EndSaga")
    class EndSagaAnnotationTest {

        @Test
        void targetIsMethod() {
            Target target = EndSaga.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.METHOD);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = EndSaga.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(EndSaga.class.getDeclaredMethods()).isEmpty();
        }
    }

    // --- @SagaStep ---

    @Nested
    @DisplayName("@SagaStep")
    class SagaStepAnnotationTest {

        @Test
        void targetIsMethod() {
            Target target = SagaStep.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.METHOD);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = SagaStep.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void hasAssociationPropertyAttribute() throws NoSuchMethodException {
            var method = SagaStep.class.getDeclaredMethod("associationProperty");
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        void hasRetryAttributeDefaultingToOneAttempt() throws NoSuchMethodException {
            var method = SagaStep.class.getDeclaredMethod("retry");
            assertThat(method.getReturnType()).isEqualTo(RetryPolicy.class);
            RetryPolicy policy = (RetryPolicy) method.getDefaultValue();
            assertThat(policy.maxAttempts()).isEqualTo(1);
            assertThat(policy.initialBackoffMillis()).isZero();
            assertThat(policy.multiplier()).isEqualTo(1.0);
            assertThat(policy.maxBackoffMillis()).isZero();
            assertThat(policy.retryOn()).isEmpty();
        }

        @Test
        void hasExactlyTwoAttributes() {
            assertThat(SagaStep.class.getDeclaredMethods()).hasSize(2);
        }
    }

    // --- @RetryPolicy (specs/005-step-retry) ---

    @Nested
    @DisplayName("@RetryPolicy")
    class RetryPolicyAnnotationTest {

        @Test
        void usableOnlyAsAnAttributeValue() {
            Target target = RetryPolicy.class.getAnnotation(Target.class);
            assertThat(target.value()).isEmpty();
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = RetryPolicy.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void hasTheFiveDocumentedAttributes() {
            assertThat(RetryPolicy.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
                    .containsExactlyInAnyOrder("maxAttempts", "initialBackoffMillis", "multiplier",
                            "maxBackoffMillis", "retryOn");
        }
    }

    // --- @SagaCompensation ---

    @Nested
    @DisplayName("@SagaCompensation")
    class SagaCompensationAnnotationTest {

        @Test
        void targetIsMethod() {
            Target target = SagaCompensation.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.METHOD);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = SagaCompensation.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void hasForStepAttribute() throws NoSuchMethodException {
            var method = SagaCompensation.class.getDeclaredMethod("forStep");
            assertThat(method.getReturnType()).isEqualTo(String.class);
        }

        @Test
        void hasExactlyOneAttribute() {
            assertThat(SagaCompensation.class.getDeclaredMethods()).hasSize(1);
        }
    }

    // --- @DomainEvent ---

    @Nested
    @DisplayName("@DomainEvent")
    class DomainEventAnnotationTest {

        @Test
        void targetIsType() {
            Target target = DomainEvent.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.TYPE);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = DomainEvent.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void hasExactlyOneAttribute() {
            assertThat(DomainEvent.class.getDeclaredMethods())
                    .extracting(java.lang.reflect.Method::getName)
                    .containsExactly("aggregate");
        }

        @Test
        void aggregateAttributeIsRequiredClass() throws NoSuchMethodException {
            var method = DomainEvent.class.getDeclaredMethod("aggregate");
            assertThat(method.getReturnType()).isEqualTo(Class.class);
            assertThat(method.getDefaultValue()).isNull();
        }
    }

    // --- @Command ---

    @Nested
    @DisplayName("@Command")
    class CommandAnnotationTest {

        @Test
        void targetIsType() {
            Target target = Command.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.TYPE);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = Command.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void hasOnlyTheDeliveryAttributeDefaultingToInUnitOfWork() throws NoSuchMethodException {
            // specs/007-in-memory-transport FR-010 adds delivery; before 007 @Command had no attributes.
            assertThat(Command.class.getDeclaredMethods()).extracting(java.lang.reflect.Method::getName)
                    .containsExactly("delivery");
            assertThat(Command.class.getDeclaredMethod("delivery").getDefaultValue())
                    .isEqualTo(CommandDelivery.IN_UNIT_OF_WORK);
        }
    }

    // --- @CommandHandler ---

    @Nested
    @DisplayName("@CommandHandler")
    class CommandHandlerAnnotationTest {

        @Test
        void targetIsMethodAndConstructor() {
            Target target = CommandHandler.class.getAnnotation(Target.class);
            assertThat(target.value())
                    .containsExactlyInAnyOrder(ElementType.METHOD, ElementType.CONSTRUCTOR);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = CommandHandler.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void isDocumented() {
            assertThat(CommandHandler.class.isAnnotationPresent(Documented.class)).isTrue();
        }

        @Test
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(CommandHandler.class.getDeclaredMethods()).isEmpty();
        }
    }

    // --- @Aggregate ---

    @Nested
    @DisplayName("@Aggregate")
    class AggregateAnnotationTest {

        @Test
        void targetIsType() {
            Target target = Aggregate.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.TYPE);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = Aggregate.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void isDocumented() {
            assertThat(Aggregate.class.isAnnotationPresent(Documented.class)).isTrue();
        }

        @Test
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(Aggregate.class.getDeclaredMethods()).isEmpty();
        }
    }

    // --- @AggregateIdentifier ---

    @Nested
    @DisplayName("@AggregateIdentifier")
    class AggregateIdentifierAnnotationTest {

        @Test
        void targetIsField() {
            Target target = AggregateIdentifier.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.FIELD);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = AggregateIdentifier.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void isDocumented() {
            assertThat(AggregateIdentifier.class.isAnnotationPresent(Documented.class)).isTrue();
        }

        @Test
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(AggregateIdentifier.class.getDeclaredMethods()).isEmpty();
        }
    }

    // --- @AggregateLoader ---

    @Nested
    @DisplayName("@AggregateLoader")
    class AggregateLoaderAnnotationTest {

        @Test
        void targetIsMethod() {
            Target target = AggregateLoader.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.METHOD);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = AggregateLoader.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void isDocumented() {
            assertThat(AggregateLoader.class.isAnnotationPresent(Documented.class)).isTrue();
        }

        @Test
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(AggregateLoader.class.getDeclaredMethods()).isEmpty();
        }
    }

    // --- @AggregateSaver ---

    @Nested
    @DisplayName("@AggregateSaver")
    class AggregateSaverAnnotationTest {

        @Test
        void targetIsMethod() {
            Target target = AggregateSaver.class.getAnnotation(Target.class);
            assertThat(target.value()).containsExactly(ElementType.METHOD);
        }

        @Test
        void retentionIsRuntime() {
            Retention retention = AggregateSaver.class.getAnnotation(Retention.class);
            assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        }

        @Test
        void isDocumented() {
            assertThat(AggregateSaver.class.isAnnotationPresent(Documented.class)).isTrue();
        }

        @Test
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(AggregateSaver.class.getDeclaredMethods()).isEmpty();
        }
    }
}
