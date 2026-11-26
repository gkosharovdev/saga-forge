package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which saga types subscribe to which domain event classes: a saga type subscribes to the exact
 * parameter class of each of its {@code @SagaStep} methods, as the router routes ({@code
 * specs/014-publish-once} research R4, R6). {@code @DeadlineExpired} steps subscribe to nothing.
 *
 * <p>Read by plain reflection, so it can be built from a model that has not been validated yet
 * ({@code SagaRuntime.eventTypes()} is available before {@code start()}); a step method without
 * exactly one parameter is left out, which the scanner reports at start.
 *
 * <p>Immutable; thread-safe.
 */
public final class EventSubscriptions {

  private final Map<Class<?>, Set<String>> sagaTypesByEvent;

  private EventSubscriptions(Map<Class<?>, Set<String>> sagaTypesByEvent) {
    this.sagaTypesByEvent = sagaTypesByEvent;
  }

  /**
   * @param registered the registered classes; only {@code @Saga} classes are read
   * @return the subscriptions of the registered saga types
   */
  public static EventSubscriptions of(Collection<Class<?>> registered) {
    Map<Class<?>, Set<String>> byEvent = new LinkedHashMap<>();
    for (Class<?> type : registered) {
      if (!type.isAnnotationPresent(Saga.class)) {
        continue;
      }
      for (Method method : type.getDeclaredMethods()) {
        if (method.isSynthetic()
            || method.isBridge()
            || !method.isAnnotationPresent(SagaStep.class)
            || method.isAnnotationPresent(DeadlineExpired.class)
            || method.getParameterCount() != 1) {
          continue;
        }
        byEvent
            .computeIfAbsent(method.getParameterTypes()[0], event -> new LinkedHashSet<>())
            .add(type.getName());
      }
    }
    Map<Class<?>, Set<String>> frozen = new LinkedHashMap<>();
    byEvent.forEach((event, sagaTypes) -> frozen.put(event, Set.copyOf(sagaTypes)));
    return new EventSubscriptions(Map.copyOf(frozen));
  }

  /**
   * @return every event class some registered saga type has a step for; unmodifiable
   */
  public Set<Class<?>> eventTypes() {
    return sagaTypesByEvent.keySet();
  }

  /**
   * @param eventClass the exact class of a recorded event
   * @param owningSagaType the fully qualified name of the saga type that recorded it
   * @return {@code true} if a saga type other than {@code owningSagaType} has a step for {@code
   *     eventClass}
   */
  boolean otherSagaTypesHandle(Class<?> eventClass, String owningSagaType) {
    Set<String> subscribers = sagaTypesByEvent.get(eventClass);
    if (subscribers == null) {
      return false;
    }
    return subscribers.size() > 1 || !subscribers.contains(owningSagaType);
  }
}
