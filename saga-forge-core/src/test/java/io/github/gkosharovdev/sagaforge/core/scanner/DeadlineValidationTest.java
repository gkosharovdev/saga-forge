package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent2;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent3;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rules R24 to R29 / Properties 40 and 41: saga deadlines (specs/017-saga-deadlines contracts/deadline-annotations.md).
 */
class DeadlineValidationTest {

    private final ReflectiveAnnotationScanner scanner = new ReflectiveAnnotationScanner();

    private List<String> errors(Class<?> saga) {
        return scanner.validate(saga).errors();
    }

    /** Two deadlines, one compensating timeout and one reminder that re-arms; every rule holds. */
    @Saga
    static class ValidSaga {
        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        @StartDeadline(name = "payment", duration = "PT15M", cancelledBy = "paid")
        @StartDeadline(name = "reminder", durationFrom = "reminderAfter")
        public void start(TestEvent event) {
        }

        @SagaStep(associationProperty = "orderId")
        public void paid(TestEvent2 event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent3 event) {
        }

        @StartCompensation
        @DeadlineExpired("payment")
        @SagaStep
        public void paymentExpired(DeadlineReached deadline) {
        }

        @DeadlineExpired("reminder")
        @SagaStep
        @StartDeadline(name = "reminder2", duration = "${reminder:PT1H}")
        public void remind() {
        }

        @DeadlineExpired("reminder2")
        @SagaStep
        public void remindAgain() {
        }

        @SagaCompensation(forStep = "start")
        public void undoStart() {
        }

        @SagaCompensation(forStep = "remind")
        public void undoRemind() {
        }
    }

    @Test
    @DisplayName("a saga with several deadlines and DeadlineReached parameters is valid (Property 40)")
    void validSaga() {
        assertThat(errors(ValidSaga.class)).isEmpty();
    }

    @Saga
    static class NoTimeoutStep {
        @SagaId
        private String sagaId;

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent3 event) {
        }

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        @StartDeadline(name = "payment", duration = "PT15M")
        public void start(TestEvent event) {
        }
    }

    @Saga
    static class TwoTimeoutSteps {
        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        @StartDeadline(name = "payment", duration = "PT15M")
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent3 event) {
        }

        @DeadlineExpired("payment")
        @SagaStep
        public void first() {
        }

        @DeadlineExpired("payment")
        @SagaStep
        public void second() {
        }
    }

    @Saga
    static class StartedTwice {
        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        @StartDeadline(name = "payment", duration = "PT15M")
        public void start(TestEvent event) {
        }

        @SagaStep(associationProperty = "orderId")
        @StartDeadline(name = "payment", duration = "PT5M")
        @StartDeadline(name = "payment", duration = "PT10M")
        public void again(TestEvent2 event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent3 event) {
        }

        @DeadlineExpired("payment")
        @SagaStep
        public void expired() {
        }
    }

    @Saga
    static class UnknownDeadline {
        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent3 event) {
        }

        @DeadlineExpired("payment")
        @SagaStep
        public void expired() {
        }
    }

    @Test
    @DisplayName("R24: a step starts a deadline at most once; each deadline has exactly one @DeadlineExpired step")
    void namesAndTimeoutSteps() {
        assertThat(errors(NoTimeoutStep.class)).containsExactly("@Saga class NoTimeoutStep: deadline 'payment' is"
                + " started by step start but no @DeadlineExpired step handles it");
        assertThat(errors(TwoTimeoutSteps.class)).containsExactly("@Saga class TwoTimeoutSteps: deadline 'payment'"
                + " has more than one @DeadlineExpired step: first, second");
        assertThat(errors(StartedTwice.class)).containsExactly("@Saga class StartedTwice: deadline 'payment' is"
                + " started more than once by step again");
        assertThat(errors(UnknownDeadline.class)).containsExactly("@Saga class UnknownDeadline: @DeadlineExpired"
                + " step expired names deadline 'payment', which no step starts");
    }

    @Saga
    static class Durations {
        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        @StartDeadline(name = "both", duration = "PT1M", durationFrom = "x")
        @StartDeadline(name = "neither")
        @StartDeadline(name = "garbled", duration = "15 minutes")
        @StartDeadline(name = "negative", duration = "-PT1M")
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent3 event) {
        }

        @DeadlineExpired("both") @SagaStep public void a() { }

        @DeadlineExpired("neither") @SagaStep public void b() { }

        @DeadlineExpired("garbled") @SagaStep public void c() { }

        @DeadlineExpired("negative") @SagaStep public void d() { }
    }

    @Test
    @DisplayName("R25: exactly one duration source; a fixed duration is ISO-8601 and not negative")
    void durations() {
        assertThat(errors(Durations.class)).containsExactlyInAnyOrder(
                "@Saga class Durations: deadline 'both' on step start must declare exactly one of duration and"
                        + " durationFrom",
                "@Saga class Durations: deadline 'neither' on step start must declare exactly one of duration and"
                        + " durationFrom",
                "@Saga class Durations: deadline 'garbled' on step start has duration '15 minutes', which is not an"
                        + " ISO-8601 duration",
                "@Saga class Durations: deadline 'negative' on step start has duration '-PT1M', which is negative");
    }

    @Saga
    static class Cancelling {
        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        @StartDeadline(name = "payment", duration = "PT1M", cancelledBy = {"nope", "expired"})
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent3 event) {
        }

        @DeadlineExpired("payment")
        @SagaStep
        public void expired() {
        }
    }

    @Test
    @DisplayName("R26: cancelling steps exist and are not the deadline's own timeout step")
    void cancelledBy() {
        assertThat(errors(Cancelling.class)).containsExactly(
                "@Saga class Cancelling: deadline 'payment' is cancelled by 'nope', which is not a step of this saga",
                "@Saga class Cancelling: deadline 'payment' is cancelled by its own @DeadlineExpired step expired");
    }

    @Saga
    static class Shapes {
        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "orderId")
        @StartDeadline(name = "a", duration = "PT1M")
        @StartDeadline(name = "b", duration = "PT1M")
        @StartDeadline(name = "c", duration = "PT1M")
        public void start(TestEvent event) {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void end(TestEvent3 event) {
        }

        @DeadlineExpired("a")
        @SagaStep
        public void wrongParameter(TestEvent2 event) {
        }

        @DeadlineExpired("b")
        @SagaStep(associationProperty = "orderId")
        public void withAssociation() {
        }

        @DeadlineExpired("c")
        public void notAStep() {
        }

        @SagaStep
        public void missingAssociation(TestEvent2 event) {
        }
    }

    @Test
    @DisplayName("R27: a timeout step is a @SagaStep with no association and no parameter or one DeadlineReached;"
            + " an event step still needs an associationProperty")
    void shapes() {
        assertThat(errors(Shapes.class)).contains(
                "@Saga class Shapes: @DeadlineExpired step wrongParameter must take no parameter or one"
                        + " DeadlineReached",
                "@Saga class Shapes: @DeadlineExpired step withAssociation must not declare an associationProperty",
                "@Saga class Shapes: method notAStep declares @StartDeadline or @DeadlineExpired but is not a"
                        + " @SagaStep",
                "@SagaStep method missingAssociation in Shapes must declare an associationProperty");
    }

    @Saga
    static class Lifecycle {
        @SagaId
        private String sagaId;

        @StartSaga
        @DeadlineExpired("a")
        @SagaStep
        public void startsOnDeadline() {
        }

        @EndSaga
        @SagaStep(associationProperty = "orderId")
        @StartDeadline(name = "a", duration = "PT1M")
        public void end(TestEvent3 event) {
        }
    }

    @Test
    @DisplayName("R28 and R29: a timeout step is not @StartSaga; a step that leaves RUNNING starts no deadline")
    void lifecycle() {
        assertThat(errors(Lifecycle.class)).contains(
                "@Saga class Lifecycle: @DeadlineExpired step startsOnDeadline cannot also be @StartSaga",
                "@Saga class Lifecycle: step end starts deadline 'a' but also leaves RUNNING, so it would be"
                        + " cancelled at once");
    }
}
