package io.github.gkosharovdev.sagaforge.engine.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;

/**
 * Reflection over client classes, the production counterpart of the 002 harness's {@code HarnessReflection}.
 *
 * <p>Members are looked up once, when the engine builds its model at {@code start()}, and made accessible then;
 * the lookups cached per class ({@link #aggregateIdentifier(Object)}) use a {@link ClassValue}.</p>
 *
 * <p>Invocation removes the reflection wrapper: the exception a client method or constructor threw leaves
 * {@link #invoke(Method, Object, Object...)} and {@link #construct(Constructor, Object...)} unchanged if it is a
 * {@link RuntimeException} or an {@link Error}; a checked exception is wrapped once in an
 * {@link UndeclaredThrowableException}.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
final class Reflection {

    /** Reads one named property of a bean; resolved once per class and property name. */
    @FunctionalInterface
    interface PropertyReader {

        /** The property value of {@code bean}, or empty if the property does not exist or is {@code null}. */
        Optional<Object> read(Object bean);
    }

    private static final PropertyReader ABSENT = bean -> Optional.empty();

    private static final ClassValue<Optional<Field>> IDENTIFIER_FIELDS = new ClassValue<>() {
        @Override
        protected Optional<Field> computeValue(Class<?> type) {
            return annotatedField(type, AggregateIdentifier.class);
        }
    };

    private Reflection() {
    }

    /**
     * Resolves how to read the property {@code name} of instances of {@code type}: a no-argument method named
     * {@code name} (record accessor), else a getter {@code getName()}, else a field named {@code name}. A
     * property that does not exist reads as empty.
     */
    static PropertyReader propertyReader(Class<?> type, String name) {
        Optional<Method> accessor = propertyMethod(type, name).or(() -> propertyMethod(type, getterName(name)));
        if (accessor.isPresent()) {
            Method method = accessible(accessor.get());
            return bean -> Optional.ofNullable(invoke(method, bean));
        }
        return instanceField(type, name)
                .map(Reflection::accessible)
                .<PropertyReader>map(field -> bean -> Optional.ofNullable(read(field, bean)))
                .orElse(ABSENT);
    }

    /** Reads the property {@code name} of {@code bean}, as {@link #propertyReader(Class, String)} resolves it. */
    static Optional<Object> readProperty(Object bean, String name) {
        return propertyReader(bean.getClass(), name).read(bean);
    }

    /**
     * The value of the single {@code @AggregateIdentifier} field of an event or command.
     *
     * @throws IllegalStateException if the type declares no {@code @AggregateIdentifier} field or its value is
     *                               {@code null}
     */
    static Object aggregateIdentifier(Object value) {
        Field field = IDENTIFIER_FIELDS.get(value.getClass()).orElseThrow(() -> new IllegalStateException(
                value.getClass().getName() + " declares no @AggregateIdentifier field"));
        Object id = read(field, value);
        if (id == null) {
            throw new IllegalStateException("@AggregateIdentifier " + field.getName() + " of "
                    + value.getClass().getName() + " is null");
        }
        return id;
    }

    /**
     * The {@code @SagaId} field of a saga class, made accessible.
     *
     * @throws IllegalStateException if the class declares none (the scanner rejects such a class)
     */
    static Field sagaIdField(Class<?> sagaType) {
        return annotatedField(sagaType, SagaId.class)
                .map(Reflection::accessible)
                .orElseThrow(() -> new IllegalStateException(sagaType.getName() + " declares no @SagaId field"));
    }

    /** The instance field of {@code owner} declared with exactly {@code fieldType}, made accessible, if any. */
    static Optional<Field> fieldOfType(Class<?> owner, Class<?> fieldType) {
        return instanceFields(owner).stream()
                .filter(field -> field.getType() == fieldType)
                .findFirst()
                .map(Reflection::accessible);
    }

    /** The no-argument constructor of {@code type} (any visibility), made accessible, if it declares one. */
    static Optional<Constructor<?>> noArgumentConstructor(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() == 0)
                .findFirst()
                .map(Reflection::accessible);
    }

    /** Methods declared on {@code type} itself carrying {@code marker}, made accessible. */
    static List<Method> annotatedMethods(Class<?> type, Class<? extends Annotation> marker) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .filter(method -> method.isAnnotationPresent(marker))
                .map(Reflection::accessible)
                .toList();
    }

    /** Constructors declared on {@code type} carrying {@code marker}, made accessible. */
    static List<Constructor<?>> annotatedConstructors(Class<?> type, Class<? extends Annotation> marker) {
        return Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(marker))
                .<Constructor<?>>map(Reflection::accessible)
                .toList();
    }

    /** Sets {@code field} (already accessible) on {@code target}. */
    static void write(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot set " + field, e);
        }
    }

    /**
     * Invokes {@code method} (already accessible) on {@code target} ({@code null} for a static method).
     *
     * @throws IllegalStateException if the arguments do not fit the method
     */
    static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw unwrapped(e);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot invoke " + method, e);
        }
    }

    /**
     * Invokes {@code constructor} (already accessible).
     *
     * @throws IllegalStateException if the arguments do not fit or the class cannot be instantiated
     */
    static Object construct(Constructor<?> constructor, Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw unwrapped(e);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot invoke " + constructor, e);
        }
    }

    private static RuntimeException unwrapped(InvocationTargetException wrapper) {
        Throwable cause = wrapper.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new UndeclaredThrowableException(cause, "Client code threw checked " + cause.getClass().getName());
    }

    private static Optional<Method> propertyMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (isPropertyMethod(method, name)) {
                    return Optional.of(method);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isPropertyMethod(Method method, String name) {
        return method.getName().equals(name)
                && method.getParameterCount() == 0
                && !Modifier.isStatic(method.getModifiers())
                && method.getReturnType() != void.class
                && !method.isSynthetic()
                && !method.isBridge();
    }

    private static String getterName(String name) {
        return "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static Optional<Field> instanceField(Class<?> type, String name) {
        return instanceFields(type).stream().filter(field -> field.getName().equals(name)).findFirst();
    }

    private static Optional<Field> annotatedField(Class<?> type, Class<? extends Annotation> marker) {
        return instanceFields(type).stream()
                .filter(field -> field.isAnnotationPresent(marker))
                .findFirst()
                .map(Reflection::accessible);
    }

    /** Non-static, non-synthetic fields of {@code type} and its superclasses, subclass first. */
    private static List<Field> instanceFields(Class<?> type) {
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

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + field, e);
        }
    }

    private static <T extends AccessibleObject> T accessible(T member) {
        member.trySetAccessible();
        return member;
    }
}
