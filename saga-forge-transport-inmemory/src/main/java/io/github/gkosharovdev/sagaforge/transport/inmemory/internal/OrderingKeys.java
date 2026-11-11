package io.github.gkosharovdev.sagaforge.transport.inmemory.internal;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;

/**
 * Ordering keys ({@code specs/007-in-memory-transport} research §5): messages with the same key are delivered one at
 * a time, in position order. An event's key is its aggregate type and identifier; a command's is its
 * {@code @AggregateIdentifier} value with the command type, else its sending saga, else none.
 */
final class OrderingKeys {

    private static final Map<Class<?>, Optional<Field>> IDENTIFIERS = new ConcurrentHashMap<>();

    private OrderingKeys() {
    }

    static String ofEvent(Object event) {
        DomainEvent annotation = event.getClass().getAnnotation(DomainEvent.class);
        Optional<String> id = identifier(event);
        if (annotation == null || id.isEmpty()) {
            return null;
        }
        return annotation.aggregate().getName() + "#" + id.get();
    }

    static String ofCommand(Object command, CommandId commandId) {
        Optional<String> id = identifier(command);
        if (id.isPresent()) {
            return command.getClass().getName() + "#" + id.get();
        }
        return commandId.sagaScoped() ? "saga:" + commandId.sagaId() : null;
    }

    private static Optional<String> identifier(Object message) {
        Optional<Field> field = IDENTIFIERS.computeIfAbsent(message.getClass(), OrderingKeys::identifierField);
        if (field.isEmpty()) {
            return Optional.empty();
        }
        try {
            Object value = field.get().get(message);
            return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
        } catch (IllegalAccessException e) {
            return Optional.empty();
        }
    }

    private static Optional<Field> identifierField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(AggregateIdentifier.class)) {
                    try {
                        field.setAccessible(true);
                        return Optional.of(field);
                    } catch (RuntimeException inaccessible) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }
}
