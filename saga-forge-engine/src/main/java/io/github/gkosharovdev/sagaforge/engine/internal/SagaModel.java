package io.github.gkosharovdev.sagaforge.engine.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.engine.internal.Reflection.PropertyReader;

/**
 * What the engine knows about one registered {@code @Saga} class: its steps keyed by the exact event class they
 * react to, the start step, and the three fields the engine injects into a fresh saga object.
 *
 * <p>Built once per saga class at {@code start()}, from a class {@code validateAll} accepted, so the shape rules
 * (one {@code @SagaId} field, one {@code @StartSaga} step, one step per event type, single-parameter steps) are
 * already guaranteed. Every member is resolved and made accessible here, never per event.</p>
 *
 * <p>Immutable and thread-safe.</p>
 */
final class SagaModel {

    /** One {@code @SagaStep} method. */
    record Step(String name, Method method, Class<?> eventType, String associationProperty, boolean start,
            boolean end) {
    }

    /** The values the engine sets on a fresh saga object, overwriting whatever the object held. */
    record Injection(String sagaId, SagaContext context, CommandGateway gateway) {
    }

    private final Class<?> type;
    private final Map<Class<?>, Step> stepsByEventType;
    private final Map<Class<?>, PropertyReader> associationReaders;
    private final Step startStep;
    private final Field sagaIdField;
    private final Optional<Field> contextField;
    private final Optional<Field> gatewayField;
    private final Optional<Constructor<?>> noArgumentConstructor;

    private SagaModel(Class<?> type, Map<Class<?>, Step> stepsByEventType) {
        this.type = type;
        this.stepsByEventType = Map.copyOf(stepsByEventType);
        this.associationReaders = associationReaders(stepsByEventType);
        this.startStep = stepsByEventType.values().stream().filter(Step::start).findFirst()
                .orElseThrow(() -> new IllegalStateException(type.getName() + " declares no @StartSaga step"));
        this.sagaIdField = Reflection.sagaIdField(type);
        this.contextField = Reflection.fieldOfType(type, SagaContext.class);
        this.gatewayField = Reflection.fieldOfType(type, CommandGateway.class);
        this.noArgumentConstructor = Reflection.noArgumentConstructor(type);
    }

    /**
     * Reads the model of {@code sagaType}, which {@code validateAll} has accepted.
     *
     * @throws IllegalStateException if the class declares no {@code @StartSaga} step or no {@code @SagaId} field
     */
    static SagaModel of(Class<?> sagaType) {
        Map<Class<?>, Step> steps = new LinkedHashMap<>();
        for (Method method : Reflection.annotatedMethods(sagaType, SagaStep.class)) {
            Step step = stepOf(method);
            steps.put(step.eventType(), step);
        }
        return new SagaModel(sagaType, steps);
    }

    Class<?> type() {
        return type;
    }

    /** The fully qualified saga class name, as stored in {@code SagaInstance.sagaType}. */
    String typeName() {
        return type.getName();
    }

    /** The step reacting to exactly {@code eventType}, if any. */
    Optional<Step> stepFor(Class<?> eventType) {
        return Optional.ofNullable(stepsByEventType.get(eventType));
    }

    Step startStep() {
        return startStep;
    }

    /** Every step's name, sorted, for a new instance's {@code NOT_RUN} step records. */
    List<String> stepNames() {
        return stepsByEventType.values().stream().map(Step::name).sorted().toList();
    }

    /**
     * The association value {@code event} carries for {@code step}: its {@code associationProperty} read as
     * {@code String.valueOf}; empty if the property is missing, {@code null} or empty.
     */
    Optional<String> associationValue(Step step, Object event) {
        return associationReaders.get(step.eventType()).read(event)
                .map(String::valueOf)
                .filter(value -> !value.isEmpty());
    }

    /**
     * A new saga object through the no-argument constructor.
     *
     * @throws IllegalStateException if the class declares none (startup rejects that unless a
     *                               {@code SagaObjectProvider} supports the class)
     */
    Object newInstance() {
        Constructor<?> constructor = noArgumentConstructor.orElseThrow(() -> new IllegalStateException(
                "@Saga class " + type.getName() + " declares no no-argument constructor"));
        return Reflection.construct(constructor);
    }

    /** Sets the {@code @SagaId}, {@code SagaContext} and {@code CommandGateway} fields of {@code saga}. */
    void inject(Object saga, Injection injection) {
        Reflection.write(sagaIdField, saga, injection.sagaId());
        contextField.ifPresent(field -> Reflection.write(field, saga, injection.context()));
        gatewayField.ifPresent(field -> Reflection.write(field, saga, injection.gateway()));
    }

    /** Runs {@code step} on {@code saga} with {@code event}; the step's own exception leaves unchanged. */
    void run(Step step, Object saga, Object event) {
        Reflection.invoke(step.method(), saga, event);
    }

    private static Step stepOf(Method method) {
        SagaStep sagaStep = method.getAnnotation(SagaStep.class);
        return new Step(method.getName(), method, method.getParameterTypes()[0], sagaStep.associationProperty(),
                method.isAnnotationPresent(StartSaga.class), method.isAnnotationPresent(EndSaga.class));
    }

    private static Map<Class<?>, PropertyReader> associationReaders(Map<Class<?>, Step> steps) {
        Map<Class<?>, PropertyReader> readers = new LinkedHashMap<>();
        steps.values().forEach(step -> readers.put(step.eventType(),
                Reflection.propertyReader(step.eventType(), step.associationProperty())));
        return Map.copyOf(readers);
    }
}
