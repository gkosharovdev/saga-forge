package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReserveItems;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.scanner.ReflectiveAnnotationScanner;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.NoSagaIdSaga;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.AuditSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.Case;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.NoteService;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.fanout.ReviewSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.InvalidCompensationSaga;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.RejectingDispatcher;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.SagaWithoutNoArgConstructor;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.ServiceWithExtraParameter;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.UnloadableAggregate;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code start()} reports every {@code validateAll} error and every engine error in one
 * {@link SagaRuntimeStartupException}, and a refused engine accepts nothing (specs/004-saga-engine tasks.md T097, User
 * Story 6 scenarios 1 to 5, SC-006, FR-034 to FR-036, quickstart.md scenario 18).
 */
class StartupValidationTest {

    static final String UNLOADABLE = "@Aggregate UnloadableAggregate has instance-method @CommandHandler methods but"
            + " no @AggregateLoader and no AggregateResolver supporting it";
    static final String EXTRA_PARAMETER = "@CommandHandler ServiceWithExtraParameter.handle parameter #2 Clock cannot"
            + " be resolved: no HandlerParameterResolver supports it";
    static final String NO_ARG_CONSTRUCTOR = "@Saga class SagaWithoutNoArgConstructor must declare a no-argument"
            + " constructor, or a SagaObjectProvider supporting it must be registered";

    private static final List<Class<?>> BASE_MODEL = List.of(ThreeStepSaga.class, ReservationService.class,
            InvoiceService.class, ShipmentService.class);

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final RecordingLogger logger = new RecordingLogger();

    /** The plug-ins of one engine under test; everything else is fixed. */
    private record Plugins(List<AggregateResolver> aggregateResolvers,
            List<HandlerParameterResolver> parameterResolvers, Optional<SagaObjectProvider> sagaObjects,
            EventDispatcher dispatcher) {

        static Plugins none() {
            return new Plugins(List.of(), List.of(), Optional.empty(), new RecordingEventDispatcher());
        }

        Plugins with(AggregateResolver resolver) {
            return new Plugins(List.of(resolver), parameterResolvers, sagaObjects, dispatcher);
        }

        Plugins with(HandlerParameterResolver resolver) {
            return new Plugins(aggregateResolvers, List.of(resolver), sagaObjects, dispatcher);
        }

        Plugins with(SagaObjectProvider provider) {
            return new Plugins(aggregateResolvers, parameterResolvers, Optional.of(provider), dispatcher);
        }

        Plugins with(EventDispatcher replacement) {
            return new Plugins(aggregateResolvers, parameterResolvers, sagaObjects, replacement);
        }
    }

    /** A built, not yet started engine over the base model plus {@code extra}. */
    private SagaRuntime engine(Plugins plugins, Class<?>... extra) {
        SagaRuntime.Builder builder = SagaRuntime.builder()
                .register(registered(extra).toArray(Class<?>[]::new))
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(plugins.dispatcher())
                .handlerInstances(new MapHandlerInstanceProvider().register(new ReservationService())
                        .register(new InvoiceService()).register(new ShipmentService()))
                .logger(logger);
        plugins.aggregateResolvers().forEach(builder::aggregateResolver);
        plugins.parameterResolvers().forEach(builder::parameterResolver);
        plugins.sagaObjects().ifPresent(builder::sagaObjects);
        return builder.build();
    }

    private static List<Class<?>> registered(Class<?>... extra) {
        return Stream.concat(BASE_MODEL.stream(), Arrays.stream(extra)).toList();
    }

    private static StartupReport refusal(SagaRuntime engine) {
        SagaRuntimeStartupException refused = catchThrowableOfType(SagaRuntimeStartupException.class, engine::start);
        assertThat(refused).as("start() should have refused the engine").isNotNull();
        assertThat(engine.isStarted()).isFalse();
        return refused.report();
    }

    /** Supports exactly one aggregate type, loading nothing. */
    private static AggregateResolver supporting(Class<?> aggregateType) {
        return new AggregateResolver() {
            @Override
            public boolean supports(Class<?> type) {
                return type == aggregateType;
            }

            @Override
            public Optional<Object> load(Class<?> type, Object id) {
                return Optional.empty();
            }

            @Override
            public void save(Object aggregate) {
            }
        };
    }

    /** Supports every parameter of one type. */
    private static HandlerParameterResolver resolving(Class<?> parameterType) {
        return new HandlerParameterResolver() {
            @Override
            public boolean supports(Executable handler, Parameter parameter) {
                return parameter.getType() == parameterType;
            }

            @Override
            public Object resolve(Executable handler, Parameter parameter) {
                return Clock.systemUTC();
            }
        };
    }

    /** Supports the given saga types; creates {@code SagaWithoutNoArgConstructor} with a dependency. */
    private static SagaObjectProvider providing(Set<Class<?>> sagaTypes) {
        return new SagaObjectProvider() {
            @Override
            public boolean supports(Class<?> sagaType) {
                return sagaTypes.contains(sagaType);
            }

            @Override
            public Object newInstance(Class<?> sagaType) {
                return sagaType == SagaWithoutNoArgConstructor.class
                        ? new SagaWithoutNoArgConstructor("dependency") : new ThreeStepSaga();
            }
        };
    }

    @Nested
    @DisplayName("each error alone")
    class EachErrorAlone {

        @Test
        @DisplayName("US6-1: an aggregate with instance handlers and no loader or supporting resolver")
        void unloadableAggregate() {
            StartupReport report = refusal(engine(Plugins.none(), UnloadableAggregate.class));

            assertThat(report.validation().isValid()).isTrue();
            assertThat(report.allErrors()).containsExactly(UNLOADABLE);
        }

        @Test
        @DisplayName("a resolver supporting the aggregate clears that error")
        void supportingResolverClears() {
            SagaRuntime engine = engine(Plugins.none().with(supporting(UnloadableAggregate.class)),
                    UnloadableAggregate.class);

            engine.start();

            assertThat(engine.isStarted()).isTrue();
        }

        @Test
        @DisplayName("US6-2: a handler parameter no HandlerParameterResolver supports")
        void unresolvableParameter() {
            StartupReport report = refusal(engine(Plugins.none(), ServiceWithExtraParameter.class));

            assertThat(report.allErrors()).containsExactly(EXTRA_PARAMETER);
        }

        @Test
        @DisplayName("a parameter resolver supporting the type clears that error")
        void parameterResolverClears() {
            SagaRuntime engine = engine(Plugins.none().with(resolving(Clock.class)), ServiceWithExtraParameter.class);

            engine.start();

            assertThat(engine.isStarted()).isTrue();
        }

        @Test
        @DisplayName("US6-3: a saga without a no-argument constructor and no provider")
        void sagaWithoutNoArgConstructor() {
            StartupReport report = refusal(engine(Plugins.none(), SagaWithoutNoArgConstructor.class));

            assertThat(report.allErrors()).containsExactly(NO_ARG_CONSTRUCTOR);
        }

        @Test
        @DisplayName("a provider supporting every saga type clears that error")
        void providerClears() {
            SagaRuntime engine = engine(Plugins.none().with(providing(Set.of(ThreeStepSaga.class,
                    SagaWithoutNoArgConstructor.class))), SagaWithoutNoArgConstructor.class);

            engine.start();

            assertThat(engine.isStarted()).isTrue();
        }

        @Test
        @DisplayName("a registered provider that does not support a saga type")
        void providerNotSupporting() {
            StartupReport report = refusal(engine(Plugins.none().with(providing(Set.of()))));

            assertThat(report.allErrors()).containsExactly(
                    "@Saga class ThreeStepSaga is not supported by the registered SagaObjectProvider");
        }

        @Test
        @DisplayName("a saga violating R20, R21 and R22 is refused with all three errors in one report (006 T021)")
        void invalidCompensationDeclarations() {
            StartupReport report = refusal(engine(Plugins.none(), InvalidCompensationSaga.class));

            assertThat(report.allErrors()).contains(InvalidCompensationSaga.R20, InvalidCompensationSaga.R21,
                    InvalidCompensationSaga.R22);
            assertThat(report.engineErrors()).isEmpty();
        }

        @Test
        @DisplayName("a model validateAll rejects")
        void invalidModel() {
            StartupReport report = refusal(engine(Plugins.none(), NoSagaIdSaga.class));

            List<String> expected = new ReflectiveAnnotationScanner().validateAll(registered(NoSagaIdSaga.class))
                    .allErrors();
            assertThat(expected).isNotEmpty();
            assertThat(report.allErrors()).containsExactlyElementsOf(expected);
            assertThat(report.engineErrors()).isEmpty();
        }

        @Test
        @DisplayName("a dispatcher rejecting a saga type")
        void dispatcherRejects() {
            SagaRuntime engine = engine(Plugins.none().with(new RejectingDispatcher(ThreeStepSaga.class)));

            SagaRuntimeStartupException refused = catchThrowableOfType(SagaRuntimeStartupException.class,
                    engine::start);

            assertThat(refused.report().allErrors()).containsExactly(
                    "EventDispatcher rejected @Saga class ThreeStepSaga: " + RejectingDispatcher.REJECTION);
            assertThat(refused.getCause()).hasMessage(RejectingDispatcher.REJECTION);
        }
    }

    @Test
    @DisplayName("US6-4: every error together, in one report, validateAll errors first, each once")
    void allErrorsTogether() {
        RejectingDispatcher dispatcher = new RejectingDispatcher(ThreeStepSaga.class);
        Class<?>[] extra = {NoSagaIdSaga.class, UnloadableAggregate.class, ServiceWithExtraParameter.class,
            SagaWithoutNoArgConstructor.class};

        StartupReport report = refusal(engine(Plugins.none().with(dispatcher), extra));

        List<String> validationErrors = new ReflectiveAnnotationScanner().validateAll(registered(extra)).allErrors();
        List<String> expected = new ArrayList<>(validationErrors);
        expected.addAll(List.of(UNLOADABLE, EXTRA_PARAMETER, NO_ARG_CONSTRUCTOR));
        assertThat(report.allErrors()).containsExactlyInAnyOrderElementsOf(expected).doesNotHaveDuplicates();
        assertThat(report.allErrors().subList(0, validationErrors.size())).isEqualTo(validationErrors);
        // A model that is not runnable is never registered with the dispatcher.
        assertThat(dispatcher.registeredSagaTypes()).isEmpty();
        assertThat(logger.byEvent("saga.startup.refused").get(0).keys())
                .containsEntry("errors", String.valueOf(expected.size()));
        assertThat(logger.byEvent("saga.startup.refused")).hasSize(expected.size() + 1);
    }

    @Test
    @DisplayName("US6-5: a refused engine rejects receive, send and publish and touches no store")
    void refusedEngineAcceptsNothing() {
        SagaRuntime engine = engine(Plugins.none(), UnloadableAggregate.class);
        refusal(engine);

        assertThatThrownBy(() -> engine.receive(new OrderPlaced("order-1", 250L, 3)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.commandGateway().send(new ReserveItems("order-1", 3)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.eventPublisher().publish(new OrderPlaced("order-1", 250L, 3)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(boundary.begins()).isZero();
        assertThat(storage.committed().all()).isEmpty();
        assertThatThrownBy(engine::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("FR-036: on success every saga type is registered with the dispatcher, in order, before any event")
    void successRegistersEverySagaFirst() {
        RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
        SagaRuntime engine = SagaRuntime.builder()
                .register(AuditSaga.class, ThreeStepSaga.class, ReviewSaga.class, NoteService.class, Case.class,
                        ReservationService.class, InvoiceService.class, ShipmentService.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(dispatcher)
                .handlerInstances(new MapHandlerInstanceProvider())
                .build();

        engine.start();

        assertThat(dispatcher.registeredSagaTypes())
                .containsExactly(AuditSaga.class, ThreeStepSaga.class, ReviewSaga.class);
        assertThat(boundary.begins()).isZero();
        assertThatThrownBy(engine::start).isInstanceOf(IllegalStateException.class);
        assertThat(engine.isStarted()).isTrue();
    }
}
