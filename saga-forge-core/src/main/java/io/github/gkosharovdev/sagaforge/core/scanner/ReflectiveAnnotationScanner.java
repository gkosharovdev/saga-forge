package io.github.gkosharovdev.sagaforge.core.scanner;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.RetryPolicy;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaCompensation;
import io.github.gkosharovdev.sagaforge.core.annotation.StartCompensation;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;

/**
 * Reflection-based implementation of {@link AnnotationScanner} that validates
 * all annotation rules for {@code @Saga}-annotated classes, and with {@code validateAll} a whole model of
 * sagas, handlers, loaders, savers, aggregates, events, and commands, in a single pass.
 *
 * <p>Validation is split into focused methods, each responsible for one rule group.
 * {@code @Saga} class rules ({@code validate} and {@code validateAll}):
 * <ul>
 *   <li>{@code validateClassLevel} — R1: @Saga present, class is concrete</li>
 *   <li>{@code validateSagaIdField} — R2: exactly one @SagaId field of type String</li>
 *   <li>{@code validateSagaContextField} — at most one field of type {@code SagaContext} (optional)</li>
 *   <li>{@code validateCommandGatewayField} — at most one field of type {@code CommandGateway} (optional)</li>
 *   <li>{@code validateSagaSteps} — R5: @SagaStep parameter rules (task 4.3)</li>
 *   <li>{@code validateStepReturnTypes} — every @SagaStep method returns {@code void}</li>
 *   <li>{@code validateUniqueStepEventTypes} — no two @SagaStep methods react to the same event type</li>
 *   <li>{@code validateNoCommandHandlers} — a @Saga class declares no @CommandHandler method</li>
 *   <li>{@code validateLifecycle} — R3: @StartSaga/@EndSaga cardinality and co-occurrence (task 4.3)</li>
 *   <li>{@code validateCompensation} — R6: @SagaCompensation.forStep validity (task 4.4)</li>
 *   <li>{@code validateStartCompensation} — R20: @StartCompensation placement (006)</li>
 *   <li>{@code validateCompensationShape} — R21: compensation method shape (006)</li>
 *   <li>{@code validateCompensationTargets} — R22: one compensation per step, none for a trigger step (006)</li>
 *   <li>{@code validateRetryPolicies} — R19: every @SagaStep.retry policy is valid (specs/005-step-retry,
 *       Property 29)</li>
 * </ul>
 * Handler, loader, and saver host rules ({@code validateAll} only):
 * <ul>
 *   <li>{@code validateCommandHandlerParameters} — each @CommandHandler method or constructor declares
 *       exactly one @Command-typed parameter</li>
 *   <li>{@code validateLoaders} — each @AggregateLoader takes one parameter and returns an @Aggregate type</li>
 *   <li>{@code validateSavers} — each @AggregateSaver takes exactly one @Aggregate-typed parameter</li>
 * </ul>
 * Identifier rules for @Aggregate, @DomainEvent, and @Command types, passed in or reached from a signature
 * ({@code validateAll} only):
 * <ul>
 *   <li>{@code validateIdentifierFields} — exactly one @AggregateIdentifier field on each @Aggregate and
 *       @DomainEvent type, at most one on each @Command type</li>
 *   <li>{@code validateEventAggregate} — @DomainEvent.aggregate names an @Aggregate class</li>
 * </ul>
 * Model rules across all hosts ({@code validateAll} only, reported as model errors):
 * <ul>
 *   <li>{@code validateUniqueCommandHandlers} — one @CommandHandler per @Command type</li>
 *   <li>{@code validateOutboxCommands} — a passed-in @Command(delivery = OUTBOX) type has a handler (R23)</li>
 *   <li>{@code validateUniqueAggregateMembers} — one @AggregateLoader and one @AggregateSaver per aggregate</li>
 *   <li>{@code validateInstanceHandlerCommandIdentifiers} — a command handled by an @Aggregate instance
 *       method declares an @AggregateIdentifier field</li>
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
        validateSagaContextField(sagaClass, errors);
        validateCommandGatewayField(sagaClass, errors);
        Map<String, Method> stepMethods = validateSagaSteps(sagaClass, errors);
        validateStepReturnTypes(sagaClass, stepMethods, errors);
        validateUniqueStepEventTypes(sagaClass, stepMethods, errors);
        validateNoCommandHandlers(sagaClass, errors);
        validateLifecycle(sagaClass, errors);
        validateCompensation(sagaClass, stepMethods, errors);
        validateRetryPolicies(sagaClass, stepMethods, errors);
        validateStartCompensation(sagaClass, errors);
        validateCompensationShape(sagaClass, errors);
        validateCompensationTargets(sagaClass, stepMethods, errors);

        return new SagaValidationResult(sagaClass, errors);
    }

    /**
     * At most one field of type {@link CommandGateway}. The field is optional (zero is valid) and is
     * recognized by its declared type only, with no marker annotation.
     */
    private void validateCommandGatewayField(Class<?> sagaClass, List<String> errors) {
        int count = 0;
        for (Field field : sagaClass.getDeclaredFields()) {
            if (field.getType().equals(CommandGateway.class)) {
                count++;
            }
        }

        if (count > 1) {
            errors.add("@Saga class " + sagaClass.getSimpleName()
                    + " must declare at most one CommandGateway field, found " + count);
        }
    }

    /**
     * Every {@code @SagaStep} method returns {@code void}; commands are dispatched through the
     * {@link CommandGateway} instead (002 FR-006).
     */
    private void validateStepReturnTypes(Class<?> sagaClass, Map<String, Method> stepMethods,
                                         List<String> errors) {
        String className = sagaClass.getSimpleName();
        for (Method method : sortedByName(stepMethods.values())) {
            if (method.getReturnType() != void.class) {
                errors.add("@SagaStep method " + method.getName() + " in " + className
                        + " must return void; dispatch commands through the CommandGateway instead");
            }
        }
    }

    /**
     * No two {@code @SagaStep} methods react to the same event type (002 FR-001). Only steps with exactly
     * one parameter are considered; other parameter shapes are reported by {@code validateSagaSteps}.
     * One error is reported per duplicated event type, ordered by the type's simple name, and the
     * offending method names are listed in ascending lexicographic order so the message is deterministic.
     */
    private void validateUniqueStepEventTypes(Class<?> sagaClass, Map<String, Method> stepMethods,
                                              List<String> errors) {
        Map<Class<?>, List<String>> methodsByEventType = new TreeMap<>(
                Comparator.comparing((Class<?> type) -> type.getSimpleName()).thenComparing(Class::getName));
        for (Method method : stepMethods.values()) {
            if (method.getParameterCount() == 1) {
                methodsByEventType.computeIfAbsent(method.getParameterTypes()[0], type -> new ArrayList<>())
                        .add(method.getName());
            }
        }

        for (Map.Entry<Class<?>, List<String>> entry : methodsByEventType.entrySet()) {
            List<String> methodNames = entry.getValue();
            if (methodNames.size() > 1) {
                Collections.sort(methodNames);
                errors.add("@Saga class " + sagaClass.getSimpleName()
                        + " has more than one @SagaStep reacting to " + entry.getKey().getSimpleName()
                        + ": " + String.join(", ", methodNames));
            }
        }
    }

    /**
     * A {@code @Saga} class declares no {@code @CommandHandler} method (002 FR-010); handlers live on
     * aggregates or services.
     */
    private void validateNoCommandHandlers(Class<?> sagaClass, List<String> errors) {
        String className = sagaClass.getSimpleName();
        for (Method method : sortedByName(List.of(sagaClass.getDeclaredMethods()))) {
            if (method.isAnnotationPresent(CommandHandler.class)) {
                errors.add("@Saga class " + className
                        + " must not declare @CommandHandler method " + method.getName());
            }
        }
    }

    private static List<Method> sortedByName(Collection<Method> methods) {
        List<Method> sorted = new ArrayList<>(methods);
        sorted.sort(Comparator.comparing(Method::getName));
        return sorted;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The classes validated are the passed-in classes followed, in discovery order, by the
     * {@code @Aggregate}, {@code @DomainEvent}, and {@code @Command} types reached from their signatures:
     * {@code @SagaStep} parameters of {@code @Saga} classes, {@code @Command} parameters of
     * {@code @CommandHandler} methods and constructors, {@code @AggregateLoader} return types, and
     * {@code @AggregateSaver} parameters. Each class is validated once. Reached types get only the
     * identifier rules; their own signatures are not followed. {@code @DomainEvent.aggregate()} is checked
     * but not followed, since it is not a signature.
     *
     * <p>Result emission: every passed-in {@code @Saga} class and every passed-in handler, loader, or saver
     * host gets a {@link SagaValidationResult}, even when valid. An {@code @Aggregate}, {@code @DomainEvent},
     * or {@code @Command} type that is not such a host gets a result only when it has errors.
     */
    @Override
    public ValidationReport validateAll(Collection<Class<?>> classes) {
        Set<Class<?>> passedIn = distinctClasses(classes);
        List<Class<?>> hosts = new ArrayList<>();
        Set<Class<?>> toValidate = new LinkedHashSet<>(passedIn);
        for (Class<?> type : passedIn) {
            if (isHost(type)) {
                hosts.add(type);
            }
            toValidate.addAll(reachedTypes(type));
        }

        List<SagaValidationResult> classResults = new ArrayList<>();
        for (Class<?> type : toValidate) {
            SagaValidationResult result = validateInModel(type, passedIn.contains(type));
            if (result != null) {
                classResults.add(result);
            }
        }

        List<String> modelErrors = new ArrayList<>();
        validateUniqueCommandHandlers(hosts, modelErrors);
        validateUniqueAggregateMembers(hosts, AggregateLoader.class, modelErrors);
        validateUniqueAggregateMembers(hosts, AggregateSaver.class, modelErrors);
        validateInstanceHandlerCommandIdentifiers(hosts, modelErrors);
        validateOutboxCommands(passedIn, hosts, modelErrors);

        return new ValidationReport(classResults, modelErrors);
    }

    private static Set<Class<?>> distinctClasses(Collection<Class<?>> classes) {
        if (classes == null) {
            throw new IllegalArgumentException("classes must not be null");
        }
        Set<Class<?>> distinct = new LinkedHashSet<>();
        for (Class<?> type : classes) {
            if (type == null) {
                throw new IllegalArgumentException("classes must not contain null elements");
            }
            distinct.add(type);
        }
        return distinct;
    }

    /**
     * Validates one class of the model. Returns {@code null} when the class gets no result (see
     * {@link #validateAll}).
     */
    private SagaValidationResult validateInModel(Class<?> type, boolean passedIn) {
        if (passedIn && type.isAnnotationPresent(Saga.class)) {
            return validate(type);
        }

        boolean host = passedIn && isHost(type);
        List<String> errors = new ArrayList<>();
        if (host) {
            validateCommandHandlerParameters(type, errors);
            validateLoaders(type, errors);
            validateSavers(type, errors);
        }
        validateIdentifierFields(type, errors);
        validateEventAggregate(type, errors);

        return host || !errors.isEmpty() ? new SagaValidationResult(type, errors) : null;
    }

    /**
     * A host is a non-{@code @Saga} class declaring a {@code @CommandHandler} method or constructor, an
     * {@code @AggregateLoader}, or an {@code @AggregateSaver}.
     */
    private static boolean isHost(Class<?> type) {
        if (type.isAnnotationPresent(Saga.class)) {
            return false;
        }
        return !handlerMembers(type).isEmpty()
                || !annotatedMethods(type, AggregateLoader.class).isEmpty()
                || !annotatedMethods(type, AggregateSaver.class).isEmpty();
    }

    /**
     * The {@code @Aggregate}, {@code @DomainEvent}, and {@code @Command} types named by the step, handler,
     * loader, and saver signatures of {@code type}, in declaration-name order.
     */
    private static Set<Class<?>> reachedTypes(Class<?> type) {
        Set<Class<?>> reached = new LinkedHashSet<>();
        if (type.isAnnotationPresent(Saga.class)) {
            for (Method step : annotatedMethods(type, SagaStep.class)) {
                addIfModelType(reached, step.getParameterTypes());
            }
            return reached;
        }
        for (Executable handler : handlerMembers(type)) {
            addIfModelType(reached, handler.getParameterTypes());
        }
        for (Method loader : annotatedMethods(type, AggregateLoader.class)) {
            addIfModelType(reached, loader.getReturnType());
        }
        for (Method saver : annotatedMethods(type, AggregateSaver.class)) {
            addIfModelType(reached, saver.getParameterTypes());
        }
        return reached;
    }

    private static void addIfModelType(Set<Class<?>> reached, Class<?>... candidates) {
        for (Class<?> candidate : candidates) {
            if (candidate.isAnnotationPresent(Aggregate.class)
                    || candidate.isAnnotationPresent(DomainEvent.class)
                    || candidate.isAnnotationPresent(Command.class)) {
                reached.add(candidate);
            }
        }
    }

    /**
     * Every {@code @CommandHandler} method or constructor declares exactly one {@code @Command}-typed
     * parameter; other parameters are permitted (002 FR-009).
     */
    private void validateCommandHandlerParameters(Class<?> type, List<String> errors) {
        for (Executable handler : handlerMembers(type)) {
            int commandCount = commandParameters(handler).size();
            if (commandCount != 1) {
                errors.add("@CommandHandler method " + memberName(handler) + " in " + type.getSimpleName()
                        + " must declare exactly one @Command parameter, found " + commandCount);
            }
        }
    }

    /**
     * Every {@code @AggregateLoader} method takes exactly one parameter and returns an {@code @Aggregate}
     * type (002 FR-013). One error per malformed method.
     */
    private void validateLoaders(Class<?> type, List<String> errors) {
        for (Method loader : annotatedMethods(type, AggregateLoader.class)) {
            if (!isWellFormedLoader(loader)) {
                errors.add("@AggregateLoader method " + loader.getName() + " in " + type.getSimpleName()
                        + " must take exactly one parameter and return an @Aggregate type");
            }
        }
    }

    /**
     * Every {@code @AggregateSaver} method takes exactly one {@code @Aggregate}-typed parameter
     * (002 FR-013). One error per malformed method.
     */
    private void validateSavers(Class<?> type, List<String> errors) {
        for (Method saver : annotatedMethods(type, AggregateSaver.class)) {
            if (!isWellFormedSaver(saver)) {
                errors.add("@AggregateSaver method " + saver.getName() + " in " + type.getSimpleName()
                        + " must take exactly one @Aggregate-typed parameter");
            }
        }
    }

    /**
     * Exactly one {@code @AggregateIdentifier} field on each {@code @Aggregate} and {@code @DomainEvent}
     * type, at most one on each {@code @Command} type (002 FR-014). Only declared fields count; record
     * components annotated {@code @AggregateIdentifier} count through their backing field.
     */
    private void validateIdentifierFields(Class<?> type, List<String> errors) {
        int count = aggregateIdentifierCount(type);
        if ((type.isAnnotationPresent(Aggregate.class) || type.isAnnotationPresent(DomainEvent.class))
                && count != 1) {
            errors.add(type.getSimpleName() + " must declare exactly one @AggregateIdentifier field, found "
                    + count);
        }
        if (type.isAnnotationPresent(Command.class) && count > 1) {
            errors.add("@Command type " + type.getSimpleName()
                    + " must declare at most one @AggregateIdentifier field, found " + count);
        }
    }

    /**
     * {@code @DomainEvent(aggregate = X.class)} names an {@code @Aggregate} class (002 FR-014).
     */
    private void validateEventAggregate(Class<?> type, List<String> errors) {
        DomainEvent event = type.getAnnotation(DomainEvent.class);
        if (event != null && !event.aggregate().isAnnotationPresent(Aggregate.class)) {
            errors.add("@DomainEvent type " + type.getSimpleName() + " names aggregate "
                    + event.aggregate().getSimpleName() + ", which is not annotated @Aggregate");
        }
    }

    /**
     * Model rule: no {@code @Command} type is handled by more than one {@code @CommandHandler} across the
     * hosts (002 FR-011). Only handlers with exactly one {@code @Command} parameter take part; others are
     * reported by {@code validateCommandHandlerParameters}.
     */
    /**
     * Model rule R23: each {@code @Command(delivery = OUTBOX)} type among the passed-in classes is handled by a
     * {@code @CommandHandler} of a passed-in host ({@code specs/007-in-memory-transport} FR-010). A outbox command
     * would otherwise fail only at delivery, after its sender committed. Types in simple-name order.
     */
    private void validateOutboxCommands(Set<Class<?>> passedIn, List<Class<?>> hosts, List<String> modelErrors) {
        Set<Class<?>> handled = new HashSet<>();
        for (Class<?> host : hosts) {
            for (Executable handler : handlerMembers(host)) {
                handled.addAll(commandParameters(handler));
            }
        }
        passedIn.stream()
                .filter(type -> type.isAnnotationPresent(Command.class)
                        && type.getAnnotation(Command.class).delivery() == CommandDelivery.OUTBOX)
                .filter(type -> !handled.contains(type))
                .sorted(Comparator.comparing(Class::getSimpleName))
                .forEach(type -> modelErrors.add("Command " + type.getSimpleName()
                        + " is delivered through the outbox but has no @CommandHandler"));
    }

    private void validateUniqueCommandHandlers(List<Class<?>> hosts, List<String> modelErrors) {
        Map<Class<?>, List<String>> handlersByCommand = bySimpleName();
        for (Class<?> host : hosts) {
            for (Executable handler : handlerMembers(host)) {
                List<Class<?>> commands = commandParameters(handler);
                if (commands.size() == 1) {
                    handlersByCommand.computeIfAbsent(commands.get(0), key -> new ArrayList<>())
                            .add(host.getSimpleName() + "." + memberName(handler));
                }
            }
        }
        reportDuplicates(handlersByCommand, "Command ", "@CommandHandler", modelErrors);
    }

    /**
     * Model rule: at most one {@code @AggregateLoader} (or {@code @AggregateSaver}) per aggregate type
     * (002 FR-014). Only well-formed members take part; malformed ones are reported per class.
     */
    private void validateUniqueAggregateMembers(List<Class<?>> hosts, Class<? extends Annotation> marker,
                                                List<String> modelErrors) {
        boolean loader = marker == AggregateLoader.class;
        Map<Class<?>, List<String>> membersByAggregate = bySimpleName();
        for (Class<?> host : hosts) {
            for (Method method : annotatedMethods(host, marker)) {
                if (loader ? isWellFormedLoader(method) : isWellFormedSaver(method)) {
                    Class<?> aggregate = loader ? method.getReturnType() : method.getParameterTypes()[0];
                    membersByAggregate.computeIfAbsent(aggregate, key -> new ArrayList<>())
                            .add(host.getSimpleName() + "." + method.getName());
                }
            }
        }
        reportDuplicates(membersByAggregate, "Aggregate ", "@" + marker.getSimpleName(), modelErrors);
    }

    /**
     * Model rule: a command handled by an instance (non-static) method of an {@code @Aggregate} declares an
     * {@code @AggregateIdentifier} field, so the target aggregate can be loaded (002 FR-014). Commands with
     * several identifiers are reported by {@code validateIdentifierFields} instead.
     */
    private void validateInstanceHandlerCommandIdentifiers(List<Class<?>> hosts, List<String> modelErrors) {
        for (Class<?> host : hosts) {
            if (!host.isAnnotationPresent(Aggregate.class)) {
                continue;
            }
            for (Executable handler : handlerMembers(host)) {
                List<Class<?>> commands = commandParameters(handler);
                if (handler instanceof Method && !Modifier.isStatic(handler.getModifiers())
                        && commands.size() == 1 && aggregateIdentifierCount(commands.get(0)) == 0) {
                    modelErrors.add("@Command type " + commands.get(0).getSimpleName()
                            + " is handled by @Aggregate instance method " + host.getSimpleName() + "."
                            + handler.getName() + " and must declare an @AggregateIdentifier field");
                }
            }
        }
    }

    private static Map<Class<?>, List<String>> bySimpleName() {
        return new TreeMap<>(
                Comparator.comparing((Class<?> type) -> type.getSimpleName()).thenComparing(Class::getName));
    }

    /**
     * One error per key with more than one entry; entries are listed in ascending lexicographic order of
     * the full {@code "Class.member"} string.
     */
    private static void reportDuplicates(Map<Class<?>, List<String>> entriesByType, String typeLabel,
                                         String memberLabel, List<String> modelErrors) {
        for (Map.Entry<Class<?>, List<String>> entry : entriesByType.entrySet()) {
            List<String> members = entry.getValue();
            if (members.size() > 1) {
                Collections.sort(members);
                modelErrors.add(typeLabel + entry.getKey().getSimpleName() + " has more than one "
                        + memberLabel + ": " + String.join(", ", members));
            }
        }
    }

    private static boolean isWellFormedLoader(Method loader) {
        return loader.getParameterCount() == 1 && loader.getReturnType().isAnnotationPresent(Aggregate.class);
    }

    private static boolean isWellFormedSaver(Method saver) {
        return saver.getParameterCount() == 1
                && saver.getParameterTypes()[0].isAnnotationPresent(Aggregate.class);
    }

    private static int aggregateIdentifierCount(Class<?> type) {
        int count = 0;
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(AggregateIdentifier.class)) {
                count++;
            }
        }
        return count;
    }

    private static List<Class<?>> commandParameters(Executable handler) {
        List<Class<?>> commands = new ArrayList<>();
        for (Class<?> parameterType : handler.getParameterTypes()) {
            if (parameterType.isAnnotationPresent(Command.class)) {
                commands.add(parameterType);
            }
        }
        return commands;
    }

    /**
     * The {@code @CommandHandler} methods and constructors declared by {@code type}, ordered by
     * {@link #memberName}.
     */
    private static List<Executable> handlerMembers(Class<?> type) {
        List<Executable> handlers = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(CommandHandler.class)) {
                handlers.add(constructor);
            }
        }
        handlers.addAll(annotatedMethods(type, CommandHandler.class));
        handlers.sort(Comparator.comparing(ReflectiveAnnotationScanner::memberName));
        return handlers;
    }

    /** A method's name, or the declaring class's simple name for a constructor. */
    private static String memberName(Executable member) {
        return member instanceof Constructor<?> ? member.getDeclaringClass().getSimpleName() : member.getName();
    }

    private static List<Method> annotatedMethods(Class<?> type, Class<? extends Annotation> marker) {
        List<Method> methods = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(marker)) {
                methods.add(method);
            }
        }
        return sortedByName(methods);
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
     * At most one field of type {@link SagaContext}. The field is optional (zero is valid) and is
     * recognized by its declared type only, with no marker annotation.
     */
    private void validateSagaContextField(Class<?> sagaClass, List<String> errors) {
        Field[] declaredFields = sagaClass.getDeclaredFields();
        List<Field> sagaContextFields = new ArrayList<>();

        for (Field field : declaredFields) {
            if (field.getType().equals(SagaContext.class)) {
                sagaContextFields.add(field);
            }
        }

        if (sagaContextFields.size() > 1) {
            errors.add("@Saga class " + sagaClass.getSimpleName()
                    + " must declare at most one SagaContext field, found " + sagaContextFields.size());
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
     * R19 (Property 29): each @SagaStep's {@code retry} policy is valid. One error per violated rule, steps in
     * method-name order, rules in attribute-declaration order.
     */
    private void validateRetryPolicies(Class<?> sagaClass, Map<String, Method> stepMethods, List<String> errors) {
        String className = sagaClass.getSimpleName();
        stepMethods.keySet().stream().sorted().forEach(name -> {
            RetryPolicy policy = stepMethods.get(name).getAnnotation(SagaStep.class).retry();
            String prefix = "@SagaStep method " + name + " in " + className + " has an invalid retry policy: ";
            retryPolicyViolations(policy).forEach(violation -> errors.add(prefix + violation));
        });
    }

    /** The R19 violations of {@code policy}, each as "{attribute} {reason}". */
    static List<String> retryPolicyViolations(RetryPolicy policy) {
        List<String> violations = new ArrayList<>();
        if (policy.maxAttempts() < 1) {
            violations.add("maxAttempts must be at least 1, found " + policy.maxAttempts());
        }
        if (policy.initialBackoffMillis() < 0) {
            violations.add("initialBackoffMillis must not be negative, found " + policy.initialBackoffMillis());
        }
        if (!Double.isFinite(policy.multiplier()) || policy.multiplier() < 1.0) {
            violations.add("multiplier must be a finite number of at least 1.0, found " + policy.multiplier());
        }
        if (policy.maxBackoffMillis() != 0 && policy.maxBackoffMillis() < policy.initialBackoffMillis()) {
            violations.add("maxBackoffMillis must be 0 or at least initialBackoffMillis "
                    + policy.initialBackoffMillis() + ", found " + policy.maxBackoffMillis());
        } else if (policy.maxBackoffMillis() < 0) {
            violations.add("maxBackoffMillis must not be negative, found " + policy.maxBackoffMillis());
        }
        for (Class<? extends Throwable> type : policy.retryOn()) {
            if (Error.class.isAssignableFrom(type)) {
                violations.add("retryOn must not name Error type " + type.getSimpleName());
            }
        }
        if (policy.maxAttempts() == 1) {
            if (policy.initialBackoffMillis() != 0) {
                violations.add("initialBackoffMillis must keep its default when maxAttempts is 1");
            }
            if (policy.multiplier() != 1.0) {
                violations.add("multiplier must keep its default when maxAttempts is 1");
            }
            if (policy.maxBackoffMillis() != 0) {
                violations.add("maxBackoffMillis must keep its default when maxAttempts is 1");
            }
            if (policy.retryOn().length != 0) {
                violations.add("retryOn must keep its default when maxAttempts is 1");
            }
        }
        return violations;
    }

    /**
     * R20 ({@code specs/006-saga-compensation} FR-001a): every {@code @StartCompensation} method also carries
     * {@code @SagaStep} and carries neither {@code @StartSaga} nor {@code @EndSaga}. Methods are visited in name
     * order.
     */
    private void validateStartCompensation(Class<?> sagaClass, List<String> errors) {
        String className = sagaClass.getSimpleName();
        for (Method method : sortedByName(sagaClass.getDeclaredMethods())) {
            if (!method.isAnnotationPresent(StartCompensation.class)) {
                continue;
            }
            if (!method.isAnnotationPresent(SagaStep.class)) {
                errors.add("Method " + method.getName() + " in " + className
                        + " is annotated with @StartCompensation but missing @SagaStep");
            }
            if (method.isAnnotationPresent(StartSaga.class)) {
                errors.add("@StartCompensation method " + method.getName() + " in " + className
                        + " cannot also be annotated with @StartSaga");
            }
            if (method.isAnnotationPresent(EndSaga.class)) {
                errors.add("@StartCompensation method " + method.getName() + " in " + className
                        + " cannot also be annotated with @EndSaga");
            }
        }
    }

    /**
     * R21 ({@code specs/006-saga-compensation} FR-009, FR-010): every {@code @SagaCompensation} method returns
     * {@code void}, declares no parameter or exactly one {@link CompensationTrigger} parameter, and is not a
     * {@code @SagaStep}. Methods are visited in name order.
     */
    private void validateCompensationShape(Class<?> sagaClass, List<String> errors) {
        String className = sagaClass.getSimpleName();
        for (Method method : sortedByName(sagaClass.getDeclaredMethods())) {
            if (!method.isAnnotationPresent(SagaCompensation.class)) {
                continue;
            }
            String prefix = "@SagaCompensation method " + method.getName() + " in " + className;
            if (method.getReturnType() != void.class) {
                errors.add(prefix + " must return void");
            }
            Class<?>[] parameters = method.getParameterTypes();
            boolean wellFormed = parameters.length == 0
                    || (parameters.length == 1 && parameters[0] == CompensationTrigger.class);
            if (!wellFormed) {
                errors.add(prefix + " must declare no parameter or one CompensationTrigger parameter");
            }
            if (method.isAnnotationPresent(SagaStep.class)) {
                errors.add(prefix + " cannot also be annotated with @SagaStep");
            }
        }
    }

    /**
     * R22 ({@code specs/006-saga-compensation} FR-008): at most one {@code @SagaCompensation} targets a step, and none
     * targets a {@code @StartCompensation} step. A {@code forStep} that names no {@code @SagaStep} is R6's error and is
     * skipped here. Steps are visited in name order; duplicate method names are listed in ascending order.
     */
    private void validateCompensationTargets(Class<?> sagaClass, Map<String, Method> stepMethods,
            List<String> errors) {
        String className = sagaClass.getSimpleName();
        java.util.SortedMap<String, List<String>> byStep = new java.util.TreeMap<>();
        for (Method method : sortedByName(sagaClass.getDeclaredMethods())) {
            if (!method.isAnnotationPresent(SagaCompensation.class)) {
                continue;
            }
            String forStep = method.getAnnotation(SagaCompensation.class).forStep();
            if (stepMethods.containsKey(forStep)) {
                byStep.computeIfAbsent(forStep, step -> new ArrayList<>()).add(method.getName());
            }
        }
        byStep.forEach((step, compensations) -> {
            if (compensations.size() > 1) {
                errors.add("@SagaCompensation methods " + String.join(", ", compensations) + " in " + className
                        + " both compensate step " + step);
            }
            if (stepMethods.get(step).isAnnotationPresent(StartCompensation.class)) {
                for (String compensation : compensations) {
                    errors.add("@SagaCompensation method " + compensation + " in " + className
                            + " compensates step " + step + ", which is a @StartCompensation step");
                }
            }
        });
    }

    private static List<Method> sortedByName(Method[] methods) {
        return java.util.Arrays.stream(methods)
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .sorted(java.util.Comparator.comparing(Method::getName))
                .toList();
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
