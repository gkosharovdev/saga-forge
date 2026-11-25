package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DeadlineExpired;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The deadlines one {@code @Saga} class declares (specs/017-saga-deadlines data-model.md "DeadlineModel"): which step
 * starts which deadline with which duration, which steps cancel it, and which {@code @DeadlineExpired} step runs when it
 * expires.
 *
 * <p>Built once per saga class at startup from a class {@code validateAll} accepted, so rules R24 to R29 already hold.
 * A fixed {@code duration} is parsed here; one written {@code "${key}"} or {@code "${key:default}"} is resolved through
 * the engine's property resolver first. A {@code durationFrom} is read from the instance's {@code SagaContext} each time
 * the deadline starts ({@link DeadlinePlan}).</p>
 *
 * <p>Immutable and thread-safe.</p>
 */
final class DeadlineModel {

    /**
     * One {@code @StartDeadline} on one step.
     *
     * @param name          the deadline's name; several steps may start the same one
     * @param startedBy     the step that starts it
     * @param fixed         the duration, if it is fixed or a resolved property; empty for {@code durationFrom}
     * @param durationFrom  the {@code SagaContext} attribute holding an ISO-8601 duration; {@code null} if fixed
     * @param cancelledBy   the steps whose completion cancels it
     */
    record Declaration(String name, String startedBy, Optional<Duration> fixed, String durationFrom,
            Set<String> cancelledBy) {

        Declaration {
            cancelledBy = Set.copyOf(cancelledBy);
        }
    }

    /** Thrown by {@link #of} when a {@code ${...}} duration cannot be resolved or parsed; startup reports its message. */
    static final class UnresolvedDurationException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        UnresolvedDurationException(String message) {
            super(message);
        }
    }

    private static final DeadlineModel NONE = new DeadlineModel(List.of(), Map.of());

    private final List<Declaration> declarations;
    private final Map<String, String> expiryStepByDeadline;

    private DeadlineModel(List<Declaration> declarations, Map<String, String> expiryStepByDeadline) {
        this.declarations = List.copyOf(declarations);
        this.expiryStepByDeadline = Map.copyOf(expiryStepByDeadline);
    }

    /** A model without deadlines. */
    static DeadlineModel none() {
        return NONE;
    }

    /**
     * Reads the deadlines of {@code sagaType}.
     *
     * @param properties resolves the key of a {@code ${key[:default]}} duration
     * @throws UnresolvedDurationException if a {@code ${...}} duration resolves to nothing, to no ISO-8601 duration, or
     *                                     to a negative one
     */
    static DeadlineModel of(Class<?> sagaType, Function<String, Optional<String>> properties) {
        List<Declaration> declarations = new ArrayList<>();
        Map<String, String> expirySteps = new LinkedHashMap<>();
        for (Method method : Reflection.annotatedMethods(sagaType, SagaStep.class)) {
            for (StartDeadline start : method.getAnnotationsByType(StartDeadline.class)) {
                declarations.add(declarationOf(sagaType, method.getName(), start, properties));
            }
            DeadlineExpired expired = method.getAnnotation(DeadlineExpired.class);
            if (expired != null) {
                expirySteps.put(expired.value(), method.getName());
            }
        }
        return declarations.isEmpty() && expirySteps.isEmpty() ? NONE : new DeadlineModel(declarations, expirySteps);
    }

    /**
     * The startup errors of the {@code ${key[:default]}} durations of {@code sagaType}: one message per placeholder
     * that resolves to nothing, to no ISO-8601 duration or to a negative one. Fixed durations are the scanner's (R25).
     */
    static List<String> placeholderErrors(Class<?> sagaType, Function<String, Optional<String>> properties) {
        List<String> errors = new ArrayList<>();
        for (Method method : Reflection.annotatedMethods(sagaType, SagaStep.class)) {
            for (StartDeadline start : method.getAnnotationsByType(StartDeadline.class)) {
                if (start.durationFrom().isBlank() && isPlaceholder(start.duration().strip())) {
                    try {
                        fixedDuration(sagaType, method.getName(), start, properties);
                    } catch (UnresolvedDurationException unresolved) {
                        errors.add(unresolved.getMessage());
                    }
                }
            }
        }
        return errors;
    }

    /** {@code true} if the saga declares no deadline. */
    boolean isEmpty() {
        return declarations.isEmpty();
    }

    /** The deadlines {@code stepName} starts, in declaration order. */
    List<Declaration> startedBy(String stepName) {
        return declarations.stream().filter(declaration -> declaration.startedBy().equals(stepName)).toList();
    }

    /** The names of the deadlines whose {@code cancelledBy} lists {@code stepName}. */
    Set<String> cancelledBy(String stepName) {
        Set<String> names = new LinkedHashSet<>();
        declarations.stream()
                .filter(declaration -> declaration.cancelledBy().contains(stepName))
                .forEach(declaration -> names.add(declaration.name()));
        return names;
    }

    /** The {@code @DeadlineExpired} step of the deadline {@code name}, if the saga declares one. */
    Optional<String> expiryStep(String name) {
        return Optional.ofNullable(expiryStepByDeadline.get(name));
    }

    private static Declaration declarationOf(Class<?> sagaType, String step, StartDeadline start,
            Function<String, Optional<String>> properties) {
        Optional<Duration> fixed = Optional.empty();
        String durationFrom = null;
        if (!start.durationFrom().isBlank()) {
            durationFrom = start.durationFrom();
        } else {
            fixed = Optional.of(fixedDuration(sagaType, step, start, properties));
        }
        return new Declaration(start.name(), step, fixed, durationFrom, Set.of(start.cancelledBy()));
    }

    private static Duration fixedDuration(Class<?> sagaType, String step, StartDeadline start,
            Function<String, Optional<String>> properties) {
        String declared = start.duration().strip();
        String value = declared;
        if (isPlaceholder(declared)) {
            String body = declared.substring(2, declared.length() - 1);
            int colon = body.indexOf(':');
            String key = colon < 0 ? body : body.substring(0, colon);
            Optional<String> fallback = colon < 0 ? Optional.empty() : Optional.of(body.substring(colon + 1));
            value = properties.apply(key).or(() -> fallback).orElseThrow(() -> unresolved(sagaType, start, step,
                    "has duration '" + declared + "', but no property '" + key + "' is set and it names no default"));
        }
        try {
            Duration duration = Duration.parse(value.strip());
            if (duration.isNegative()) {
                throw unresolved(sagaType, start, step, "has duration '" + declared + "', which is negative");
            }
            return duration;
        } catch (DateTimeParseException notIso) {
            throw unresolved(sagaType, start, step, "has duration '" + declared + "'"
                    + (value.equals(declared) ? "" : ", resolved to '" + value + "',")
                    + " which is not an ISO-8601 duration");
        }
    }

    private static boolean isPlaceholder(String duration) {
        return duration.startsWith("${") && duration.endsWith("}");
    }

    private static UnresolvedDurationException unresolved(Class<?> sagaType, StartDeadline start, String step,
            String problem) {
        return new UnresolvedDurationException("@Saga class " + sagaType.getSimpleName() + ": deadline '"
                + start.name() + "' on step '" + step + "' " + problem);
    }
}
