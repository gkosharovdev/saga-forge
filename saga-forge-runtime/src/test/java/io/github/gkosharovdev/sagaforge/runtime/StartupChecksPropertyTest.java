package io.github.gkosharovdev.sagaforge.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.NoSagaIdSaga;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.SagaWithoutNoArgConstructor;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.ServiceWithExtraParameter;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.startup.UnloadableAggregate;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * Properties 21 to 24: for any mix of runnable and unrunnable members added to a valid base model, one startup report
 * holds an error for exactly the unsupported members, and a refused engine accepts no event.
 *
 * <p>**Validates: Requirements FR-019, FR-034, FR-035, FR-036, SC-006**</p>
 */
class StartupChecksPropertyTest {

    /** How a member is added: left out, added and supported by a plug-in, or added unsupported. */
    enum Member { ABSENT, SUPPORTED, UNSUPPORTED }

    @Property(tries = 100)
    void reportHoldsExactlyTheUnsupportedMembers(@ForAll Member unloadable, @ForAll Member extraParameter,
            @ForAll Member noArgSaga, @ForAll boolean invalidSaga) {
        List<Class<?>> registered = new ArrayList<>(List.of(ThreeStepSaga.class, ReservationService.class,
                InvoiceService.class, ShipmentService.class));
        if (invalidSaga) {
            registered.add(NoSagaIdSaga.class);
        }
        List<String> expectedEngineErrors = new ArrayList<>();
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        SagaRuntime.Builder builder = SagaRuntime.builder()
                .storage(new TransactionalInMemoryStorageProvider(boundary))
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(new RecordingEventDispatcher())
                .handlerInstances(new MapHandlerInstanceProvider());

        add(unloadable, UnloadableAggregate.class, registered, expectedEngineErrors,
                StartupValidationTest.UNLOADABLE, () -> builder.aggregateResolver(supportingUnloadable()));
        add(extraParameter, ServiceWithExtraParameter.class, registered, expectedEngineErrors,
                StartupValidationTest.EXTRA_PARAMETER, () -> builder.parameterResolver(resolvingClock()));
        add(noArgSaga, SagaWithoutNoArgConstructor.class, registered, expectedEngineErrors,
                StartupValidationTest.NO_ARG_CONSTRUCTOR, () -> builder.sagaObjects(providingEverySaga()));
        SagaRuntime engine = builder.register(registered.toArray(Class<?>[]::new)).build();

        boolean runnable = expectedEngineErrors.isEmpty() && !invalidSaga;
        if (runnable) {
            engine.start();
            assertThat(engine.isStarted()).isTrue();
            return;
        }
        SagaRuntimeStartupException refused = catchThrowableOfType(SagaRuntimeStartupException.class, engine::start);
        assertThat(refused).isNotNull();
        assertThat(refused.report().engineErrors()).containsExactlyElementsOf(expectedEngineErrors);
        assertThat(refused.report().validation().isValid()).isEqualTo(!invalidSaga);
        assertThatThrownBy(() -> engine.receive(new OrderPlaced("order-1", 250L, 3)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(boundary.begins()).isZero();
    }

    /** Registers {@code type} unless absent; a supported member gets its plug-in, an unsupported one an error. */
    private static void add(Member member, Class<?> type, List<Class<?>> registered, List<String> expectedErrors,
            String error, Runnable registerPlugin) {
        if (member == Member.ABSENT) {
            return;
        }
        registered.add(type);
        if (member == Member.SUPPORTED) {
            registerPlugin.run();
        } else {
            expectedErrors.add(error);
        }
    }

    private static AggregateResolver supportingUnloadable() {
        return new AggregateResolver() {
            @Override
            public boolean supports(Class<?> aggregateType) {
                return aggregateType == UnloadableAggregate.class;
            }

            @Override
            public Optional<Object> load(Class<?> aggregateType, Object aggregateId) {
                return Optional.empty();
            }

            @Override
            public void save(Object aggregate) {
            }
        };
    }

    private static HandlerParameterResolver resolvingClock() {
        return new HandlerParameterResolver() {
            @Override
            public boolean supports(Executable handler, Parameter parameter) {
                return parameter.getType() == Clock.class;
            }

            @Override
            public Object resolve(Executable handler, Parameter parameter) {
                return Clock.systemUTC();
            }
        };
    }

    /** Supports every saga type; never asked to create one here. */
    private static SagaObjectProvider providingEverySaga() {
        return new SagaObjectProvider() {
            @Override
            public boolean supports(Class<?> sagaType) {
                return true;
            }

            @Override
            public Object newInstance(Class<?> sagaType) {
                try {
                    Constructor<?> constructor = sagaType.getDeclaredConstructors()[0];
                    return constructor.getParameterCount() == 0 ? constructor.newInstance()
                            : constructor.newInstance("dependency");
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }
}
