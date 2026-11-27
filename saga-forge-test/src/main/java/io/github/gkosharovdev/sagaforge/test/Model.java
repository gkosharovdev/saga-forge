package io.github.gkosharovdev.sagaforge.test;

import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The reflection the fixture needs over the registered model; never invokes client code. Domain
 * roles and identifiers come from {@link DomainTypes}, so every enabled vocabulary counts
 * (specs/016-marker-spi-jmolecules).
 */
final class Model {

  private Model() {}

  /** The command types the command handlers of {@code host} take. */
  static Set<Class<?>> handledCommandTypes(Class<?> host, DomainTypes domainTypes) {
    Set<Class<?>> types = new LinkedHashSet<>();
    List<Executable> handlers = new ArrayList<>(List.of(host.getDeclaredConstructors()));
    handlers.addAll(methods(host));
    for (Executable handler : handlers) {
      if (domainTypes.commandHandler(handler).isEmpty()) {
        continue;
      }
      for (Class<?> parameter : handler.getParameterTypes()) {
        if (domainTypes.isCommand(parameter)) {
          types.add(parameter);
          break;
        }
      }
    }
    return types;
  }

  /** {@code forStep} → compensation method name, for {@code sagaType}. */
  static Map<String, String> compensations(Class<?> sagaType) {
    Map<String, String> byStep = new LinkedHashMap<>();
    for (Method method : methods(sagaType)) {
      SagaCompensation compensation = method.getAnnotation(SagaCompensation.class);
      if (compensation != null) {
        byStep.put(compensation.forStep(), method.getName());
      }
    }
    return byStep;
  }

  /** A new object of {@code type} through its no-argument constructor, if it declares one. */
  static Optional<Object> newInstance(Class<?> type) {
    try {
      Constructor<?> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      return Optional.of(constructor.newInstance());
    } catch (NoSuchMethodException absent) {
      return Optional.empty();
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new IllegalStateException("cannot construct " + type.getName(), e);
    }
  }

  static boolean hasNoArgumentConstructor(Class<?> type) {
    try {
      type.getDeclaredConstructor();
      return true;
    } catch (NoSuchMethodException absent) {
      return false;
    }
  }

  /**
   * A field-by-field copy of {@code object} if its class has a no-argument constructor, else {@code
   * object} itself (records and other immutable aggregates need no copy).
   */
  static Object shallowCopy(Object object) {
    Class<?> type = object.getClass();
    if (type.isRecord()) {
      return object;
    }
    Optional<Object> copy = newInstance(type);
    if (copy.isEmpty()) {
      return object;
    }
    try {
      for (Class<?> level = type;
          level != null && level != Object.class;
          level = level.getSuperclass()) {
        for (Field field : level.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers())) {
            continue;
          }
          field.setAccessible(true);
          field.set(copy.get(), field.get(object));
        }
      }
      return copy.get();
    } catch (IllegalAccessException | RuntimeException e) {
      return object;
    }
  }

  /** Simple name of a fully qualified, possibly nested, class name. */
  static String simpleName(String className) {
    int cut = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
    return cut < 0 ? className : className.substring(cut + 1);
  }

  private static List<Method> methods(Class<?> type) {
    List<Method> methods = new ArrayList<>();
    for (Class<?> level = type;
        level != null && level != Object.class;
        level = level.getSuperclass()) {
      methods.addAll(List.of(level.getDeclaredMethods()));
    }
    return methods;
  }
}
