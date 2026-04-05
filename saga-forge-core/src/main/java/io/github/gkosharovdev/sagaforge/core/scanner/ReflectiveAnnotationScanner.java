package io.github.gkosharovdev.sagaforge.core.scanner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;

/**
 * Reflection-based implementation of {@link AnnotationScanner} that validates
 * all annotation rules for {@code @Saga}-annotated classes in a single pass.
 *
 * <p>Validation is split into focused methods, each responsible for one rule group:
 * <ul>
 *   <li>{@code validateClassLevel} — R1: @Saga present, class is concrete</li>
 *   <li>{@code validateSagaIdField} — R2: exactly one @SagaId field of type String</li>
 *   <li>{@code validateLifecycle} — R3: @StartSaga/@EndSaga cardinality and co-occurrence (task 4.3)</li>
 *   <li>{@code validateSagaSteps} — R5: @SagaStep parameter rules (task 4.3)</li>
 *   <li>{@code validateCompensation} — R6: @SagaCompensation.forStep validity (task 4.4)</li>
 * </ul>
 */
public class ReflectiveAnnotationScanner implements AnnotationScanner {

    @Override
    public SagaValidationResult validate(Class<?> sagaClass) {
        if (sagaClass == null) {
            throw new IllegalArgumentException("sagaClass must not be null");
        }

        List<String> errors = new ArrayList<>();

        validateClassLevel(sagaClass, errors);
        validateSagaIdField(sagaClass, errors);
        Map<String, Method> stepMethods = validateSagaSteps(sagaClass, errors);
        validateLifecycle(sagaClass, errors);
        validateCompensation(sagaClass, stepMethods, errors);

        return new SagaValidationResult(sagaClass, errors);
    }

    /**
     * R1: Class must be annotated with @Saga and must be concrete (not interface/abstract).
     */
    private void validateClassLevel(Class<?> sagaClass, List<String> errors) {
        int modifiers = sagaClass.getModifiers();
        if (sagaClass.isInterface() || Modifier.isAbstract(modifiers)) {
            errors.add("@Saga class " + sagaClass.getSimpleName()
                    + " must be a concrete class, not an interface or abstract class");
        }
    }

    /**
     * R2: Exactly one @SagaId field of type String.
     */
    private void validateSagaIdField(Class<?> sagaClass, List<String> errors) {
        Field[] declaredFields = sagaClass.getDeclaredFields();
        List<Field> sagaIdFields = new ArrayList<>();

        for (Field field : declaredFields) {
            if (field.isAnnotationPresent(SagaId.class)) {
                sagaIdFields.add(field);
            }
        }

        if (sagaIdFields.size() != 1) {
            errors.add("@Saga class " + sagaClass.getSimpleName()
                    + " must declare exactly one @SagaId field, found " + sagaIdFields.size());
        } else {
            Field sagaIdField = sagaIdFields.get(0);
            if (!sagaIdField.getType().equals(String.class)) {
                errors.add("@SagaId field " + sagaIdField.getName()
                        + " in " + sagaClass.getSimpleName() + " must be of type String");
            }
        }
    }

    /**
     * R3: Lifecycle method cardinality and co-occurrence with @SagaStep.
     * - Exactly one @StartSaga method
     * - At least one @EndSaga method
     * - All @StartSaga/@EndSaga methods must also be annotated with @SagaStep
     */
    private void validateLifecycle(Class<?> sagaClass, List<String> errors) {
        String className = sagaClass.getSimpleName();
        Method[] methods = sagaClass.getDeclaredMethods();

        List<Method> startMethods = new ArrayList<>();
        List<Method> endMethods = new ArrayList<>();

        for (Method method : methods) {
            if (method.isAnnotationPresent(StartSaga.class)) {
                startMethods.add(method);
            }
            if (method.isAnnotationPresent(EndSaga.class)) {
                endMethods.add(method);
            }
        }

        // Cardinality: exactly one @StartSaga
        if (startMethods.size() != 1) {
            errors.add("@Saga class " + className
                    + " must declare exactly one @StartSaga method, found " + startMethods.size());
        }

        // Cardinality: at least one @EndSaga
        if (endMethods.isEmpty()) {
            errors.add("@Saga class " + className
                    + " must declare at least one @EndSaga method, found 0");
        }

        // Co-occurrence: @StartSaga must also have @SagaStep
        for (Method method : startMethods) {
            if (!method.isAnnotationPresent(SagaStep.class)) {
                errors.add("Method " + method.getName() + " in " + className
                        + " is annotated with @StartSaga but missing @SagaStep");
            }
        }

        // Co-occurrence: @EndSaga must also have @SagaStep
        for (Method method : endMethods) {
            if (!method.isAnnotationPresent(SagaStep.class)) {
                errors.add("Method " + method.getName() + " in " + className
                        + " is annotated with @EndSaga but missing @SagaStep");
            }
        }
    }

    /**
     * R5: Each @SagaStep method must accept exactly one @DomainEvent-annotated parameter.
     * Returns a map of step method names to Method objects for compensation validation.
     */
    private Map<String, Method> validateSagaSteps(Class<?> sagaClass, List<String> errors) {
        String className = sagaClass.getSimpleName();
        Method[] methods = sagaClass.getDeclaredMethods();
        Map<String, Method> stepMethods = new HashMap<>();

        for (Method method : methods) {
            if (!method.isAnnotationPresent(SagaStep.class)) {
                continue;
            }

            stepMethods.put(method.getName(), method);

            int paramCount = method.getParameterCount();
            if (paramCount != 1) {
                errors.add("@SagaStep method " + method.getName() + " in " + className
                        + " must accept exactly one parameter, found " + paramCount);
                continue;
            }

            Class<?> paramType = method.getParameterTypes()[0];
            if (!paramType.isAnnotationPresent(DomainEvent.class)) {
                errors.add("@SagaStep method " + method.getName()
                        + " parameter type " + paramType.getSimpleName()
                        + " must be annotated with @DomainEvent");
            }
        }

        return stepMethods;
    }

    /**
     * R6: Each @SagaCompensation.forStep must reference an existing @SagaStep method.
     */
    private void validateCompensation(Class<?> sagaClass, Map<String, Method> stepMethods, List<String> errors) {
        String className = sagaClass.getSimpleName();
        Method[] methods = sagaClass.getDeclaredMethods();

        for (Method method : methods) {
            if (!method.isAnnotationPresent(SagaCompensation.class)) {
                continue;
            }

            String forStep = method.getAnnotation(SagaCompensation.class).forStep();

            // Check if a method with that name exists in the class
            boolean methodExists = false;
            for (Method m : methods) {
                if (m.getName().equals(forStep)) {
                    methodExists = true;
                    break;
                }
            }

            if (!methodExists) {
                errors.add("@SagaCompensation on " + method.getName()
                        + ": forStep '" + forStep
                        + "' does not reference an existing method in " + className);
            } else if (!stepMethods.containsKey(forStep)) {
                errors.add("@SagaCompensation on " + method.getName()
                        + ": forStep '" + forStep
                        + "' must reference a @SagaStep method");
            }
        }
    }
}
