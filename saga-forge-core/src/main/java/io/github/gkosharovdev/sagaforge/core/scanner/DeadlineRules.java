package io.github.gkosharovdev.sagaforge.core.scanner;

import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scanner rules R24 to R29 for saga deadlines ({@code specs/017-saga-deadlines}
 * contracts/deadline-annotations.md, Properties 40 and 41). Run by {@link
 * ReflectiveAnnotationScanner#validate(Class)} on one {@code @Saga} class.
 */
final class DeadlineRules {

  private DeadlineRules() {}

  /** Appends one message per violation, in rule order, then deadline-name and method-name order. */
  static void validate(Class<?> sagaClass, Map<String, Method> stepMethods, List<String> errors) {
    String prefix = "@Saga class " + sagaClass.getSimpleName() + ": ";
    List<Method> methods = new ArrayList<>(List.of(sagaClass.getDeclaredMethods()));
    methods.sort(Comparator.comparing(Method::getName));

    Map<String, List<String>> starters = new TreeMap<>();
    Map<String, List<String>> handlers = new TreeMap<>();
    for (Method method : methods) {
      boolean step = method.isAnnotationPresent(SagaStep.class);
      StartDeadline[] started = method.getAnnotationsByType(StartDeadline.class);
      DeadlineExpired expired = method.getAnnotation(DeadlineExpired.class);
      if (!step && (started.length > 0 || expired != null)) {
        errors.add(
            prefix
                + "method "
                + method.getName()
                + " declares @StartDeadline or @DeadlineExpired but is not a @SagaStep");
        continue;
      }
      for (StartDeadline deadline : started) {
        starters.computeIfAbsent(deadline.name(), name -> new ArrayList<>()).add(method.getName());
      }
      if (expired != null) {
        handlers.computeIfAbsent(expired.value(), name -> new ArrayList<>()).add(method.getName());
      }
    }

    validateNames(prefix, starters, handlers, errors);
    for (Method method : methods) {
      if (!method.isAnnotationPresent(SagaStep.class)) {
        continue;
      }
      for (StartDeadline deadline : method.getAnnotationsByType(StartDeadline.class)) {
        validateDuration(prefix, method, deadline, errors);
        validateCancelledBy(prefix, method, deadline, stepMethods, handlers, errors);
      }
      if (method.isAnnotationPresent(DeadlineExpired.class)) {
        validateTimeoutStep(prefix, method, errors);
      }
      validateStartsWhileRunning(prefix, method, errors);
    }
  }

  /**
   * R24 (Property 40): a step starts a name at most once, one timeout step per deadline, no timeout
   * step for an unknown deadline. Several steps may start the same deadline (a later start replaces
   * an open one), for example a timeout step that re-arms it.
   */
  private static void validateNames(
      String prefix,
      Map<String, List<String>> starters,
      Map<String, List<String>> handlers,
      List<String> errors) {
    starters.forEach(
        (name, steps) -> {
          if (name.isBlank()) {
            errors.add(prefix + "a @StartDeadline on step " + steps.get(0) + " has a blank name");
          } else {
            steps.stream()
                .filter(step -> steps.indexOf(step) != steps.lastIndexOf(step))
                .distinct()
                .forEach(
                    step ->
                        errors.add(
                            prefix
                                + "deadline '"
                                + name
                                + "' is started more than once by"
                                + " step "
                                + step));
          }
          List<String> timeoutSteps = handlers.getOrDefault(name, List.of());
          if (timeoutSteps.isEmpty() && !name.isBlank()) {
            errors.add(
                prefix
                    + "deadline '"
                    + name
                    + "' is started by step "
                    + steps.get(0)
                    + " but no @DeadlineExpired step handles it");
          } else if (timeoutSteps.size() > 1) {
            errors.add(
                prefix
                    + "deadline '"
                    + name
                    + "' has more than one @DeadlineExpired step: "
                    + String.join(", ", timeoutSteps));
          }
        });
    handlers.forEach(
        (name, steps) -> {
          if (!starters.containsKey(name)) {
            errors.add(
                prefix
                    + "@DeadlineExpired step "
                    + steps.get(0)
                    + " names deadline '"
                    + name
                    + "', which no step starts");
          }
        });
  }

  /**
   * R25 (Property 40): exactly one duration source; a fixed duration parses and is not negative.
   */
  private static void validateDuration(
      String prefix, Method method, StartDeadline deadline, List<String> errors) {
    String where = "deadline '" + deadline.name() + "' on step " + method.getName();
    boolean fixed = !deadline.duration().isBlank();
    boolean fromContext = !deadline.durationFrom().isBlank();
    if (fixed == fromContext) {
      errors.add(prefix + where + " must declare exactly one of duration and durationFrom");
      return;
    }
    if (fixed && !isPlaceholder(deadline.duration())) {
      String problem = durationProblem(deadline.duration());
      if (problem != null) {
        errors.add(
            prefix + where + " has duration '" + deadline.duration() + "', which " + problem);
      }
    }
  }

  /**
   * R26 (Property 41): every cancelling step is a step of the saga and not the deadline's own
   * timeout step.
   */
  private static void validateCancelledBy(
      String prefix,
      Method method,
      StartDeadline deadline,
      Map<String, Method> stepMethods,
      Map<String, List<String>> handlers,
      List<String> errors) {
    List<String> timeoutSteps = handlers.getOrDefault(deadline.name(), List.of());
    for (String cancelling : deadline.cancelledBy()) {
      if (!stepMethods.containsKey(cancelling)) {
        errors.add(
            prefix
                + "deadline '"
                + deadline.name()
                + "' is cancelled by '"
                + cancelling
                + "', which is not a step of this saga");
      } else if (timeoutSteps.contains(cancelling)) {
        errors.add(
            prefix
                + "deadline '"
                + deadline.name()
                + "' is cancelled by its own @DeadlineExpired"
                + " step "
                + cancelling);
      }
    }
  }

  /** R27, R28 (Property 40): the shape of a timeout step. */
  private static void validateTimeoutStep(String prefix, Method method, List<String> errors) {
    String where = "@DeadlineExpired step " + method.getName();
    Class<?>[] parameters = method.getParameterTypes();
    if (parameters.length > 1
        || (parameters.length == 1 && parameters[0] != DeadlineReached.class)) {
      errors.add(prefix + where + " must take no parameter or one DeadlineReached");
    }
    if (!method.getAnnotation(SagaStep.class).associationProperty().isBlank()) {
      errors.add(prefix + where + " must not declare an associationProperty");
    }
    if (method.isAnnotationPresent(StartSaga.class)) {
      errors.add(prefix + where + " cannot also be @StartSaga");
    }
  }

  /**
   * R29 (Property 41): a step that ends RUNNING starts no deadline, which would be cancelled at
   * once.
   */
  private static void validateStartsWhileRunning(
      String prefix, Method method, List<String> errors) {
    boolean leavesRunning =
        method.isAnnotationPresent(StartCompensation.class)
            || method.isAnnotationPresent(EndSaga.class);
    StartDeadline[] started = method.getAnnotationsByType(StartDeadline.class);
    if (leavesRunning && started.length > 0) {
      errors.add(
          prefix
              + "step "
              + method.getName()
              + " starts deadline '"
              + started[0].name()
              + "' but also leaves RUNNING, so it would be cancelled at once");
    }
  }

  static boolean isPlaceholder(String duration) {
    return duration.startsWith("${") && duration.endsWith("}");
  }

  /**
   * @return why {@code text} is not a usable duration, or {@code null} if it is
   */
  static String durationProblem(String text) {
    try {
      return Duration.parse(text).isNegative() ? "is negative" : null;
    } catch (DateTimeParseException e) {
      return "is not an ISO-8601 duration";
    }
  }
}
