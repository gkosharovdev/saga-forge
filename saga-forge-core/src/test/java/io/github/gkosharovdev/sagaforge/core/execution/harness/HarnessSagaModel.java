package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.core.scanner.ReflectiveAnnotationScanner;
import io.github.gkosharovdev.sagaforge.core.scanner.SagaValidationResult;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reflective metadata of one {@code @Saga} class registered with the saga test harness.
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <p>Built only from a class that passes {@link ReflectiveAnnotationScanner#validate(Class)}.</p>
 */
final class HarnessSagaModel {

    /** One {@code @SagaStep} method. */
    record Step(Method method, String name, Class<?> eventType, String associationProperty, boolean start,
            boolean end) {
    }

    private final Class<?> type;
    private final Field sagaIdField;
    private final List<Field> contextFields;
    private final List<Field> gatewayFields;
    private final Map<Class<?>, Step> stepsByEventType;

    private HarnessSagaModel(Class<?> type, Field sagaIdField, List<Field> contextFields, List<Field> gatewayFields,
            Map<Class<?>, Step> stepsByEventType) {
        this.type = type;
        this.sagaIdField = sagaIdField;
        this.contextFields = contextFields;
        this.gatewayFields = gatewayFields;
        this.stepsByEventType = stepsByEventType;
    }

    /**
     * Reads the metadata of {@code sagaType}.
     *
     * @throws IllegalArgumentException if the class fails saga validation
     */
    static HarnessSagaModel of(Class<?> sagaType) {
        SagaValidationResult validation = new ReflectiveAnnotationScanner().validate(sagaType);
        if (!validation.isValid()) {
            throw new IllegalArgumentException(
                    "Saga " + sagaType.getName() + " is invalid: " + validation.errors());
        }
        List<Field> fields = HarnessReflection.instanceFields(sagaType);
        Field sagaIdField = fields.stream().filter(f -> f.isAnnotationPresent(SagaId.class)).findFirst()
                .orElseThrow();
        List<Field> contextFields = fields.stream().filter(f -> f.getType() == SagaContext.class).toList();
        List<Field> gatewayFields = fields.stream().filter(f -> f.getType() == CommandGateway.class).toList();
        Map<Class<?>, Step> steps = new LinkedHashMap<>();
        for (Method method : HarnessReflection.methods(sagaType)) {
            SagaStep sagaStep = method.getAnnotation(SagaStep.class);
            if (sagaStep != null) {
                Class<?> eventType = method.getParameterTypes()[0];
                steps.put(eventType, new Step(method, method.getName(), eventType, sagaStep.associationProperty(),
                        method.isAnnotationPresent(StartSaga.class), method.isAnnotationPresent(EndSaga.class)));
            }
        }
        return new HarnessSagaModel(sagaType, sagaIdField, contextFields, gatewayFields, Map.copyOf(steps));
    }

    Class<?> type() {
        return type;
    }

    /** The step reacting to {@code eventType} (exact class), if any. */
    Optional<Step> stepFor(Class<?> eventType) {
        return Optional.ofNullable(stepsByEventType.get(eventType));
    }

    /** A fresh saga object with its {@code @SagaId}, {@code SagaContext} and {@code CommandGateway} fields set. */
    Object instantiate(String sagaId, SagaContext context, CommandGateway gateway) {
        Object saga = HarnessReflection.instantiate(type);
        HarnessReflection.set(sagaIdField, saga, sagaId);
        contextFields.forEach(field -> HarnessReflection.set(field, saga, context));
        gatewayFields.forEach(field -> HarnessReflection.set(field, saga, gateway));
        return saga;
    }
}
