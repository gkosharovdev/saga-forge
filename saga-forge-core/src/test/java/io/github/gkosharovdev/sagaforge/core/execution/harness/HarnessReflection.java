package io.github.gkosharovdev.sagaforge.core.execution.harness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;

/**
 * Reflection helpers shared by the saga test harness.
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <p>Invocation helpers unwrap {@link InvocationTargetException} and rethrow the target's own exception
 * unchanged, including checked exceptions, so the harness sees exactly what client code threw.</p>
 */
final class HarnessReflection {

    private HarnessReflection() {
    }

    /** Invokes {@code method} on {@code target}, rethrowing the method's own exception unchanged. */
    static Object invoke(Method method, Object target, Object... args) {
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw sneakyThrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke " + method, e);
        }
    }

    /** Creates an instance of {@code type} through its no-argument constructor (any visibility). */
    static Object instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InvocationTargetException e) {
            throw sneakyThrow(e.getCause());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(type.getName() + " has no no-argument constructor", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate " + type.getName(), e);
        }
    }

    /** Invokes {@code constructor} with {@code args}, rethrowing the constructor's own exception unchanged. */
    static Object construct(Constructor<?> constructor, Object... args) {
        try {
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw sneakyThrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot invoke " + constructor, e);
        }
    }

    /** {@code true} if {@code type} (or a superclass) declares a non-static {@code @AggregateIdentifier} field. */
    static boolean declaresAggregateIdentifier(Class<?> type) {
        return instanceFields(type).stream().anyMatch(field -> field.isAnnotationPresent(AggregateIdentifier.class));
    }

    /** Sets {@code field} on {@code target} (any visibility). */
    static void set(Field field, Object target, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot set " + field, e);
        }
    }

    /** Reads {@code field} from {@code target} (any visibility). */
    static Object get(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + field, e);
        }
    }

    /** Non-static fields of {@code type} and its superclasses, subclass first. */
    static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    /** Non-synthetic methods declared on {@code type} and its superclasses, subclass first. */
    static List<Method> methods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.isSynthetic() && !method.isBridge()) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    /**
     * Reads the property {@code name} of {@code bean}: a no-argument method named {@code name} (record
     * accessor), else a getter {@code getName()}, else a field named {@code name}.
     *
     * @return the property value, or empty if the property does not exist or is {@code null}
     */
    static Optional<Object> readProperty(Object bean, String name) {
        Class<?> type = bean.getClass();
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String candidate : List.of(name, getter)) {
            for (Method method : methods(type)) {
                if (method.getName().equals(candidate) && method.getParameterCount() == 0
                        && !Modifier.isStatic(method.getModifiers()) && method.getReturnType() != void.class) {
                    return Optional.ofNullable(invoke(method, bean));
                }
            }
        }
        for (Field field : instanceFields(type)) {
            if (field.getName().equals(name)) {
                return Optional.ofNullable(get(field, bean));
            }
        }
        return Optional.empty();
    }

    /**
     * Reads the value of the single {@code @AggregateIdentifier} field of {@code value}.
     *
     * @throws IllegalStateException if the type declares no {@code @AggregateIdentifier} field or its value
     *                               is {@code null}
     */
    static Object aggregateIdentifier(Object value) {
        for (Field field : instanceFields(value.getClass())) {
            if (field.isAnnotationPresent(AggregateIdentifier.class)) {
                Object id = get(field, value);
                if (id == null) {
                    throw new IllegalStateException(
                            "@AggregateIdentifier " + field.getName() + " of " + value + " is null");
                }
                return id;
            }
        }
        throw new IllegalStateException(value.getClass().getName() + " declares no @AggregateIdentifier field");
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
