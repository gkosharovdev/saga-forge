package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies annotation metadata (target, retention, attributes) for all 8
 * Saga Forge annotations via reflection.
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
        void hasExactlyOneAttribute() {
            assertThat(SagaStep.class.getDeclaredMethods()).hasSize(1);
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
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(DomainEvent.class.getDeclaredMethods()).isEmpty();
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
        void isMarkerAnnotationWithNoAttributes() {
            assertThat(Command.class.getDeclaredMethods()).isEmpty();
        }
    }
}
