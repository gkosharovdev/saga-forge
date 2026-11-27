package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.runtime.internal.Reflection.PropertyReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the engine knows about one registered {@code @Saga} class: its steps keyed by the exact
 * event class they react to, the start step, its {@code @SagaCompensation} methods keyed by the
 * step they undo (006), and the three fields the engine injects into a fresh saga object.
 *
 * <p>Built once per saga class at {@code start()}, from a class {@code validateAll} accepted, so
 * the shape rules (one {@code @SagaId} field, one {@code @StartSaga} step, one step per event type,
 * single-parameter steps) are already guaranteed. Every member is resolved and made accessible
 * here, never per event.
 *
 * <p>Immutable and thread-safe.
 */
final class SagaModel {

  /**
   * One {@code @SagaStep} method. A {@code @DeadlineExpired} step (017) has {@code deadlineName}
   * set, reacts to {@link DeadlineReached}, has no association property and {@code takesEvent} only
   * if it declares the parameter.
   */
  record Step(
      String name,
      Method method,
      Class<?> eventType,
      String associationProperty,
      boolean start,
      boolean end,
      boolean startsCompensation,
      String deadlineName,
      boolean takesEvent) {

    Step(
        String name,
        Method method,
        Class<?> eventType,
        String associationProperty,
        boolean start,
        boolean end,
        boolean startsCompensation) {
      this(
          name, method, eventType, associationProperty, start, end, startsCompensation, null, true);
    }

    /** {@code true} for a {@code @DeadlineExpired} step (017). */
    boolean expiresDeadline() {
      return deadlineName != null;
    }

    /** The step's {@code @SagaStep(retry = ...)} policy (005). */
    RetryPolicy retryPolicy() {
      return method.getAnnotation(SagaStep.class).retry();
    }
  }

  /**
   * One {@code @SagaCompensation} method (006): undoes {@code forStep}; {@code takesTrigger} if it
   * declares a {@code CompensationTrigger} parameter (rule R21 allows that or none).
   */
  record Compensation(String name, Method method, String forStep, boolean takesTrigger) {}

  /** The values the engine sets on a fresh saga object, overwriting whatever the object held. */
  record Injection(
      String sagaId, SagaContext context, CommandGateway gateway, SagaMetadata metadata) {}

  private final Class<?> type;
  private final List<Step> steps;
  private final Map<Class<?>, Step> stepsByEventType;
  private final Map<String, Step> stepsByDeadline;
  private final DeadlineModel deadlines;
  private final Map<Class<?>, PropertyReader> associationReaders;
  private final Step startStep;
  private final Field sagaIdField;
  private final Optional<Field> contextField;
  private final Optional<Field> gatewayField;
  private final Optional<Field> metadataField;
  private final Optional<Constructor<?>> noArgumentConstructor;
  private final Map<String, Compensation> compensationsByStep;

  private SagaModel(Class<?> type, List<Step> steps, DeadlineModel deadlines) {
    this.type = type;
    this.steps = List.copyOf(steps);
    this.deadlines = deadlines;
    this.compensationsByStep = compensations(type);
    Map<Class<?>, Step> stepsByEventType = new LinkedHashMap<>();
    Map<String, Step> stepsByDeadline = new LinkedHashMap<>();
    steps.forEach(
        step -> {
          if (step.expiresDeadline()) {
            stepsByDeadline.put(step.deadlineName(), step);
          } else {
            stepsByEventType.put(step.eventType(), step);
          }
        });
    this.stepsByEventType = Map.copyOf(stepsByEventType);
    this.stepsByDeadline = Map.copyOf(stepsByDeadline);
    this.associationReaders = associationReaders(stepsByEventType);
    this.startStep =
        stepsByEventType.values().stream()
            .filter(Step::start)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException(type.getName() + " declares no @StartSaga step"));
    this.sagaIdField = Reflection.sagaIdField(type);
    this.contextField = Reflection.fieldOfType(type, SagaContext.class);
    this.gatewayField = Reflection.fieldOfType(type, CommandGateway.class);
    this.metadataField = Reflection.fieldOfType(type, SagaMetadata.class);
    this.noArgumentConstructor = Reflection.noArgumentConstructor(type);
  }

  /**
   * Reads the model of {@code sagaType}, which {@code validateAll} has accepted.
   *
   * @throws IllegalStateException if the class declares no {@code @StartSaga} step or no
   *     {@code @SagaId} field
   */
  static SagaModel of(Class<?> sagaType) {
    return of(sagaType, key -> Optional.empty());
  }

  /**
   * Reads the model of {@code sagaType}, which {@code validateAll} has accepted, resolving {@code
   * ${...}} deadline durations through {@code properties} (017).
   *
   * @throws IllegalStateException if the class declares no {@code @StartSaga} step or no
   *     {@code @SagaId} field, or a deadline duration cannot be resolved ({@link
   *     DeadlineModel.UnresolvedDurationException})
   */
  static SagaModel of(Class<?> sagaType, Function<String, Optional<String>> properties) {
    List<Step> steps =
        Reflection.annotatedMethods(sagaType, SagaStep.class).stream()
            .map(SagaModel::stepOf)
            .toList();
    return new SagaModel(sagaType, steps, DeadlineModel.of(sagaType, properties));
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

  /** The {@code @DeadlineExpired} step of the deadline {@code name}, if any (017). */
  Optional<Step> expiryStep(String deadlineName) {
    return Optional.ofNullable(stepsByDeadline.get(deadlineName));
  }

  /** The deadlines this saga declares (017). */
  DeadlineModel deadlines() {
    return deadlines;
  }

  /**
   * The {@code @SagaCompensation} undoing the step named {@code stepName}, if one is declared
   * (006).
   */
  Optional<Compensation> compensationFor(String stepName) {
    return Optional.ofNullable(compensationsByStep.get(stepName));
  }

  /** {@code true} if a {@code @SagaStep} method is named {@code methodName}. */
  boolean hasStep(String methodName) {
    return steps.stream().anyMatch(step -> step.name().equals(methodName));
  }

  /** The {@code @SagaCompensation} method named {@code methodName}, if one is declared (012). */
  Optional<Compensation> compensationNamed(String methodName) {
    return compensationsByStep.values().stream()
        .filter(compensation -> compensation.name().equals(methodName))
        .findFirst();
  }

  /**
   * The retry policy of the step named {@code stepName}; the default one-attempt policy if no step
   * has that name.
   */
  RetryPolicy retryPolicyOf(String stepName) {
    return steps.stream()
        .filter(step -> step.name().equals(stepName))
        .findFirst()
        .map(Step::retryPolicy)
        .orElseGet(() -> DefaultRetryPolicy.INSTANCE);
  }

  /**
   * The default one-attempt {@code @RetryPolicy}; used to classify a failed compensation, which
   * never retries.
   */
  static RetryPolicy defaultRetryPolicy() {
    return DefaultRetryPolicy.INSTANCE;
  }

  /** Holder of the default {@code @RetryPolicy} value, read from {@code @SagaStep.retry()}. */
  private static final class DefaultRetryPolicy {

    static final RetryPolicy INSTANCE;

    static {
      try {
        INSTANCE = (RetryPolicy) SagaStep.class.getDeclaredMethod("retry").getDefaultValue();
      } catch (NoSuchMethodException impossible) {
        throw new ExceptionInInitializerError(impossible);
      }
    }
  }

  /** Every step's name, sorted, for a new instance's {@code NOT_RUN} step records. */
  List<String> stepNames() {
    return steps.stream().map(Step::name).sorted().toList();
  }

  /**
   * The association value {@code event} carries for {@code step}: its {@code associationProperty}
   * read as {@code String.valueOf}; empty if the property is missing, {@code null} or empty.
   */
  Optional<String> associationValue(Step step, Object event) {
    return associationReaders
        .get(step.eventType())
        .read(event)
        .map(String::valueOf)
        .filter(value -> !value.isEmpty());
  }

  /**
   * A new saga object through the no-argument constructor.
   *
   * @throws IllegalStateException if the class declares none (startup rejects that unless a {@code
   *     SagaObjectProvider} supports the class)
   */
  Object newInstance() {
    Constructor<?> constructor =
        noArgumentConstructor.orElseThrow(
            () ->
                new IllegalStateException(
                    "@Saga class " + type.getName() + " declares no no-argument constructor"));
    return Reflection.construct(constructor);
  }

  /**
   * Sets the {@code @SagaId}, {@code SagaContext} and {@code CommandGateway} fields of {@code
   * saga}.
   */
  void inject(Object saga, Injection injection) {
    Reflection.write(sagaIdField, saga, injection.sagaId());
    contextField.ifPresent(field -> Reflection.write(field, saga, injection.context()));
    gatewayField.ifPresent(field -> Reflection.write(field, saga, injection.gateway()));
    metadataField.ifPresent(field -> Reflection.write(field, saga, injection.metadata()));
  }

  /**
   * Runs {@code step} on {@code saga} with {@code event}, or without an argument if the step takes
   * none (a {@code @DeadlineExpired} step, 017); the step's own exception leaves unchanged.
   */
  void run(Step step, Object saga, Object event) {
    if (step.takesEvent()) {
      Reflection.invoke(step.method(), saga, event);
    } else {
      Reflection.invoke(step.method(), saga);
    }
  }

  /**
   * Runs {@code compensation} on {@code saga}, passing {@code trigger} if it declares the
   * parameter; the method's own exception leaves unchanged.
   */
  void run(Compensation compensation, Object saga, CompensationTrigger trigger) {
    if (compensation.takesTrigger()) {
      Reflection.invoke(compensation.method(), saga, trigger);
    } else {
      Reflection.invoke(compensation.method(), saga);
    }
  }

  private static Step stepOf(Method method) {
    SagaStep sagaStep = method.getAnnotation(SagaStep.class);
    DeadlineExpired expired = method.getAnnotation(DeadlineExpired.class);
    if (expired != null) {
      return new Step(
          method.getName(),
          method,
          DeadlineReached.class,
          "",
          false,
          method.isAnnotationPresent(EndSaga.class),
          method.isAnnotationPresent(StartCompensation.class),
          expired.value(),
          method.getParameterCount() == 1);
    }
    return new Step(
        method.getName(),
        method,
        method.getParameterTypes()[0],
        sagaStep.associationProperty(),
        method.isAnnotationPresent(StartSaga.class),
        method.isAnnotationPresent(EndSaga.class),
        method.isAnnotationPresent(StartCompensation.class));
  }

  /** Rule R22 guarantees one compensation per step; a later duplicate would be ignored. */
  private static Map<String, Compensation> compensations(Class<?> sagaType) {
    Map<String, Compensation> byStep = new LinkedHashMap<>();
    for (Method method : Reflection.annotatedMethods(sagaType, SagaCompensation.class)) {
      String forStep = method.getAnnotation(SagaCompensation.class).forStep();
      byStep.putIfAbsent(
          forStep,
          new Compensation(method.getName(), method, forStep, method.getParameterCount() == 1));
    }
    return Map.copyOf(byStep);
  }

  private static Map<Class<?>, PropertyReader> associationReaders(Map<Class<?>, Step> steps) {
    Map<Class<?>, PropertyReader> readers = new LinkedHashMap<>();
    steps
        .values()
        .forEach(
            step ->
                readers.put(
                    step.eventType(),
                    Reflection.propertyReader(step.eventType(), step.associationProperty())));
    return Map.copyOf(readers);
  }
}
