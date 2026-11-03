package io.github.gkosharovdev.sagaforge.core.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.NotADomainEvent;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent2;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventRecorder;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;

/**
 * Unit tests for {@link DomainEvents} and its thread binding {@link DomainEventScope}
 * (contracts/event-publisher.md, spec FR-014, FR-015).
 */
class DomainEventsTest {

    @Test
    void recordWithoutActiveUnitOfWorkThrowsIllegalState() {
        assertThatThrownBy(() -> DomainEvents.record(new TestEvent("o-1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordRejectsNull() {
        List<Object> recorded = new ArrayList<>();
        DomainEventScope.runWith(recorded::add, () ->
                assertThatThrownBy(() -> DomainEvents.record(null))
                        .isInstanceOf(IllegalArgumentException.class));
        assertThat(recorded).isEmpty();
    }

    @Test
    void recordRejectsNonDomainEvent() {
        List<Object> recorded = new ArrayList<>();
        DomainEventScope.runWith(recorded::add, () ->
                assertThatThrownBy(() -> DomainEvents.record(new NotADomainEvent("o-1")))
                        .isInstanceOf(IllegalArgumentException.class));
        assertThat(recorded).isEmpty();
    }

    @Test
    void recordRejectsInvalidArgumentEvenWithoutActiveUnitOfWork() {
        assertThatThrownBy(() -> DomainEvents.record(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainEvents.record(new NotADomainEvent("o-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordDelegatesToBoundRecorder() {
        List<Object> recorded = new ArrayList<>();
        TestEvent event = new TestEvent("o-1");
        DomainEventScope.runWith(recorded::add, () -> DomainEvents.record(event));
        assertThat(recorded).containsExactly(event);
    }

    @Test
    void captureReturnsEventsInRecordingOrder() {
        TestEvent first = new TestEvent("o-1");
        TestEvent2 second = new TestEvent2("o-1");
        TestEvent third = new TestEvent("o-2");

        List<Object> captured = DomainEvents.capture(() -> {
            DomainEvents.record(first);
            DomainEvents.record(second);
            DomainEvents.record(third);
        });

        assertThat(captured).containsExactly(first, second, third);
        assertThatThrownBy(() -> captured.add(first)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(DomainEventScope.current()).isEmpty();
    }

    @Test
    void captureInsideActiveUnitOfWorkThrowsIllegalState() {
        DomainEventScope.runWith(event -> { }, () ->
                assertThatThrownBy(() -> DomainEvents.capture(() -> { }))
                        .isInstanceOf(IllegalStateException.class));
    }

    @Test
    void runWithRejectsNullArguments() {
        assertThatThrownBy(() -> DomainEventScope.runWith(null, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainEventScope.runWith(event -> { }, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nestedRunWithRestoresOuterRecorder() {
        List<Object> outer = new ArrayList<>();
        List<Object> inner = new ArrayList<>();
        TestEvent before = new TestEvent("before");
        TestEvent nested = new TestEvent("nested");
        TestEvent after = new TestEvent("after");

        DomainEventScope.runWith(outer::add, () -> {
            DomainEvents.record(before);
            DomainEventScope.runWith(inner::add, () -> DomainEvents.record(nested));
            DomainEvents.record(after);
        });

        assertThat(outer).containsExactly(before, after);
        assertThat(inner).containsExactly(nested);
        assertThat(DomainEventScope.current()).isEmpty();
    }

    @Test
    void nestedRunWithRestoresOuterRecorderWhenWorkThrows() {
        List<Object> outer = new ArrayList<>();
        DomainEventRecorder outerRecorder = outer::add;
        RuntimeException failure = new RuntimeException("boom");
        TestEvent after = new TestEvent("after");

        DomainEventScope.runWith(outerRecorder, () -> {
            assertThatThrownBy(() -> DomainEventScope.runWith(event -> { }, () -> {
                throw failure;
            })).isSameAs(failure);
            assertThat(DomainEventScope.current()).containsSame(outerRecorder);
            DomainEvents.record(after);
        });

        assertThat(outer).containsExactly(after);
        assertThat(DomainEventScope.current()).isEmpty();
    }

    @Test
    void bindingIsClearedWhenOutermostWorkThrows() {
        RuntimeException failure = new RuntimeException("boom");
        assertThatThrownBy(() -> DomainEventScope.runWith(event -> { }, () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(DomainEventScope.current()).isEmpty();
    }

    @Test
    void bindingIsNotVisibleFromAnotherThread() {
        AtomicReference<Optional<DomainEventRecorder>> seenElsewhere = new AtomicReference<>();
        AtomicReference<Throwable> recordFailure = new AtomicReference<>();
        List<Object> recorded = new ArrayList<>();

        DomainEventScope.runWith(recorded::add, () -> {
            Thread other = new Thread(() -> {
                seenElsewhere.set(DomainEventScope.current());
                try {
                    DomainEvents.record(new TestEvent("o-1"));
                } catch (Throwable t) {
                    recordFailure.set(t);
                }
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        assertThat(seenElsewhere.get()).isEmpty();
        assertThat(recordFailure.get()).isInstanceOf(IllegalStateException.class);
        assertThat(recorded).isEmpty();
    }
}
