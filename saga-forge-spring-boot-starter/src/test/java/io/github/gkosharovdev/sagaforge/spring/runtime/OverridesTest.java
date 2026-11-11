package io.github.gkosharovdev.sagaforge.spring.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;
import io.github.gkosharovdev.sagaforge.runtime.support.ConcurrentSagaContextStore;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.spring.fixtures.order.OrderEvents.OrderCreatedEvent;
import io.github.gkosharovdev.sagaforge.spring.host.SpringDataAggregateResolver;
import io.github.gkosharovdev.sagaforge.spring.host.SpringHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.spring.host.SpringHandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.spring.host.SpringSagaObjectProvider;
import io.github.gkosharovdev.sagaforge.spring.support.StarterRunner;
import io.github.gkosharovdev.sagaforge.spring.transport.TransportLifecycle;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;

/** Every replaceable starter bean gives way to an application bean ({@code specs/008-spring-boot-starter} FR-014, SC-004). */
class OverridesTest {

    private final ApplicationContextRunner runner = StarterRunner.withDoubles(StarterRunner.runner(), Plain.class);

    @Test
    @DisplayName("by default the Spring host SPIs, the runtime and its lifecycle are assembled and started")
    void defaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SagaObjectProvider.class)).isInstanceOf(SpringSagaObjectProvider.class);
            assertThat(context.getBean(HandlerInstanceProvider.class)).isInstanceOf(SpringHandlerInstanceProvider.class);
            assertThat(context.getBean(HandlerParameterResolver.class))
                    .isInstanceOf(SpringHandlerParameterResolver.class);
            assertThat(context.getBean(AggregateResolver.class)).isInstanceOf(SpringDataAggregateResolver.class);
            assertThat(context.getBean(SagaRuntime.class).isStarted()).isTrue();
        });
    }

    @Test
    @DisplayName("saga-forge.enabled=false assembles nothing")
    void disabled() {
        runner.withPropertyValues("saga-forge.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(SagaRuntime.class)
                    .doesNotHaveBean(SagaRuntimeLifecycle.class).doesNotHaveBean(SagaObjectProvider.class);
        });
    }

    @Test
    @DisplayName("runtime.max-total-retry-backoff reaches the runtime: a longer retry policy fails startup")
    void retryBackoffProperty() {
        ApplicationContextRunner retrying = StarterRunner.withDoubles(StarterRunner.runner(), Retrying.class);
        retrying.run(context -> assertThat(context).hasNotFailed());
        retrying.withPropertyValues("saga-forge.runtime.max-total-retry-backoff=1s").run(context ->
                assertThat(context.getStartupFailure()).rootCause().isInstanceOf(SagaRuntimeStartupException.class)
                        .hasMessageContaining("Retrying.on").hasMessageContaining("1000 ms"));
    }

    @Nested
    @DisplayName("an application bean replaces the starter's")
    class Replaced {

        @Test
        void sagaRuntime() {
            InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
            SagaRuntime own = SagaRuntime.builder().register(Plain.class)
                    .storage(new TransactionalInMemoryStorageProvider(boundary))
                    .contextStore(new ConcurrentSagaContextStore()).transactions(boundary)
                    .dispatcher(new TestTransport()).build();
            runner.withBean(SagaRuntime.class, () -> own).run(context -> {
                assertThat(context.getBean(SagaRuntime.class)).isSameAs(own);
                assertThat(own.isStarted()).as("the lifecycle still starts it").isTrue();
            });
        }

        @Test
        void storageAndContextStore() {
            runner.run(context -> {
                assertThat(context.getBean(StorageProvider.class)).isInstanceOf(TransactionalInMemoryStorageProvider.class);
                assertThat(context.getBean(SagaContextStore.class)).isInstanceOf(ConcurrentSagaContextStore.class);
            });
        }

        @Test
        void transactionBoundary() {
            runner.run(context -> assertThat(context.getBean(TransactionBoundary.class))
                    .isInstanceOf(InMemoryTransactionBoundary.class));
        }

        @Test
        void inMemoryTransport() {
            InMemoryMessageStore store = new InMemoryMessageStore();
            InMemoryTransport own = InMemoryTransport.builder().messageStore(store)
                    .transactions(new InMemoryTransactionBoundary()).build();
            runner.withBean(MessageStore.class, () -> store).withBean(InMemoryTransport.class, () -> own)
                    .run(context -> {
                        assertThat(context.getBean(InMemoryTransport.class)).isSameAs(own);
                        assertThat(context).hasSingleBean(TransportLifecycle.class);
                    });
        }

        @Test
        void sagaObjectProvider() {
            SagaObjectProvider own = new SagaObjectProvider() {
                @Override
                public boolean supports(Class<?> sagaType) {
                    return true;
                }

                @Override
                public Object newInstance(Class<?> sagaType) {
                    return new Plain();
                }
            };
            runner.withBean(SagaObjectProvider.class, () -> own)
                    .run(context -> assertThat(context.getBean(SagaObjectProvider.class)).isSameAs(own));
        }

        @Test
        void handlerInstanceProvider() {
            HandlerInstanceProvider own = type -> null;
            runner.withBean(HandlerInstanceProvider.class, () -> own)
                    .run(context -> assertThat(context.getBean(HandlerInstanceProvider.class)).isSameAs(own));
        }

        @Test
        void handlerParameterResolver() {
            HandlerParameterResolver own = new HandlerParameterResolver() {
                @Override
                public boolean supports(Executable handler, Parameter parameter) {
                    return false;
                }

                @Override
                public Object resolve(Executable handler, Parameter parameter) {
                    return null;
                }
            };
            runner.withBean(HandlerParameterResolver.class, () -> own)
                    .run(context -> assertThat(context.getBean(HandlerParameterResolver.class)).isSameAs(own));
        }

        @Test
        void aggregateResolver() {
            AggregateResolver own = new AggregateResolver() {
                @Override
                public boolean supports(Class<?> aggregateType) {
                    return false;
                }

                @Override
                public java.util.Optional<Object> load(Class<?> aggregateType, Object identifier) {
                    return java.util.Optional.empty();
                }

                @Override
                public void save(Object aggregate) {
                    // nothing
                }
            };
            runner.withBean(AggregateResolver.class, () -> own)
                    .run(context -> assertThat(context.getBean(AggregateResolver.class)).isSameAs(own));
        }
    }

    @Saga
    public static class Plain {

        @SagaId
        private String sagaId;

        @StartSaga
        @EndSaga
        @SagaStep(associationProperty = "orderId")
        public void on(OrderCreatedEvent event) {
            // ends at once
        }
    }

    @Saga
    public static class Retrying {

        @SagaId
        private String sagaId;

        @StartSaga
        @EndSaga
        @SagaStep(associationProperty = "orderId", retry = @RetryPolicy(maxAttempts = 3, initialBackoffMillis = 1_000,
                multiplier = 2.0, maxBackoffMillis = 2_000))
        public void on(OrderCreatedEvent event) {
            // ends at once
        }
    }
}
