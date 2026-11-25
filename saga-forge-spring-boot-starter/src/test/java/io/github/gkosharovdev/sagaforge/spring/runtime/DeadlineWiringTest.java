package io.github.gkosharovdev.sagaforge.spring.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import io.github.gkosharovdev.sagaforge.runtime.support.ConcurrentSagaContextStore;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.SchedulingOutbox;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.spring.support.StarterRunner;

/**
 * The starter hands deadlines what they need ({@code specs/017-saga-deadlines} tasks.md T037): Spring's
 * {@code Environment} resolves {@code ${...}} durations, and an application {@link Clock} bean is the runtime's clock.
 */
class DeadlineWiringTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);

    private ApplicationContextRunner runner() {
        return StarterRunner.runner()
                .withBean(TransactionBoundary.class, () -> boundary)
                .withBean(StorageProvider.class, () -> storage)
                .withBean(SagaContextStore.class, ConcurrentSagaContextStore::new)
                .withBean(EventDispatcher.class, TestTransport::new)
                .withBean(Outbox.class, () -> new SchedulingOutbox(boundary))
                .withBean(SagaModelContributor.class, () -> () -> List.of(Lapsing.class));
    }

    @Test
    @DisplayName("a ${key} duration is read from the Environment and counted from the application's Clock")
    void propertyAndClock() {
        runner().withPropertyValues("checkout.hold-lapse=PT5M")
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(SagaRuntime.class).receive(new HoldPlaced("h-1"));

                    SagaInstance instance = storage.committed().all().get(0);
                    assertThat(instance.openDeadlines().get("lapse").dueAt())
                            .isEqualTo(NOW.plus(Duration.ofMinutes(5)));
                });
    }

    @Test
    @DisplayName("an unset property without a default refuses to start, naming saga, deadline and key")
    void unsetProperty() {
        runner().run(context -> assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOf(SagaRuntimeStartupException.class)
                .hasMessageContaining("deadline 'lapse'").hasMessageContaining("no property 'checkout.hold-lapse'"));
    }

    @Aggregate
    public static final class Hold {

        @AggregateIdentifier
        private String holdId;
    }

    @DomainEvent(aggregate = Hold.class)
    public record HoldPlaced(@AggregateIdentifier String holdId) {
    }

    @DomainEvent(aggregate = Hold.class)
    public record HoldConfirmed(@AggregateIdentifier String holdId) {
    }

    @Saga
    public static class Lapsing {

        @SagaId
        private String sagaId;

        @StartSaga
        @SagaStep(associationProperty = "holdId")
        @StartDeadline(name = "lapse", duration = "${checkout.hold-lapse}")
        public void on(HoldPlaced event) {
            // opens the deadline
        }

        @EndSaga
        @SagaStep(associationProperty = "holdId")
        public void on(HoldConfirmed event) {
            // confirmed in time
        }

        @StartCompensation
        @DeadlineExpired("lapse")
        @SagaStep
        public void lapsed() {
            // the hold lapsed
        }
    }
}
