package io.github.gkosharovdev.sagaforge.core.execution.harness;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.command.AggregateNotFoundException;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the saga test harness loads and saves aggregates for {@link HarnessCommandGateway} routes, with the
 * precedence of contracts/command-gateway.md and spec FR-013.
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <ul>
 *   <li><b>Load</b> ({@link #load(Class, Object)}): the client {@code @AggregateLoader} for the exact type if one
 *       was registered, else the resolver if it {@linkplain AggregateResolver#supports supports} the type. A
 *       {@code null} loader result or an empty resolver result is an {@link AggregateNotFoundException}.</li>
 *   <li><b>Save</b> ({@link #save(Class, Object)}): the client {@code @AggregateSaver} for the exact type, else the
 *       supporting resolver, else nothing (the client's own unit of work would persist the change).</li>
 * </ul>
 *
 * <p>Loader and saver methods are found on repository hosts: a loader is a non-static {@code @AggregateLoader}
 * method with exactly one parameter (the identifier) returning an {@code @Aggregate} type; a saver is a non-static
 * {@code @AggregateSaver} method with exactly one {@code @Aggregate}-typed parameter. Both are keyed by that
 * aggregate type; at most one of each per type across all hosts (FR-014).</p>
 */
final class HarnessAggregateAccess {

    private record HostMethod(Object host, Method method) {
    }

    private final Map<Class<?>, HostMethod> loaders;
    private final Map<Class<?>, HostMethod> savers;
    private final AggregateResolver resolver;

    private HarnessAggregateAccess(Map<Class<?>, HostMethod> loaders, Map<Class<?>, HostMethod> savers,
            AggregateResolver resolver) {
        this.loaders = loaders;
        this.savers = savers;
        this.resolver = resolver;
    }

    /**
     * Scans {@code repositories} for loader and saver methods.
     *
     * @throws IllegalArgumentException if a host has no loader or saver, a loader or saver has an unsupported
     *                                  shape, or an aggregate type has two loaders or two savers
     */
    static HarnessAggregateAccess of(List<Object> repositories, AggregateResolver resolver) {
        Map<Class<?>, HostMethod> loaders = new LinkedHashMap<>();
        Map<Class<?>, HostMethod> savers = new LinkedHashMap<>();
        for (Object host : repositories) {
            boolean found = false;
            for (Method method : HarnessReflection.methods(host.getClass())) {
                if (method.isAnnotationPresent(AggregateLoader.class)) {
                    found = true;
                    if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                            || !method.getReturnType().isAnnotationPresent(Aggregate.class)) {
                        throw new IllegalArgumentException("Unsupported @AggregateLoader " + method
                                + ": expected a non-static method with one identifier parameter returning an"
                                + " @Aggregate type");
                    }
                    put(loaders, method.getReturnType(), new HostMethod(host, method), "@AggregateLoader");
                }
                if (method.isAnnotationPresent(AggregateSaver.class)) {
                    found = true;
                    if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1
                            || !method.getParameterTypes()[0].isAnnotationPresent(Aggregate.class)) {
                        throw new IllegalArgumentException("Unsupported @AggregateSaver " + method
                                + ": expected a non-static method with one @Aggregate-typed parameter");
                    }
                    put(savers, method.getParameterTypes()[0], new HostMethod(host, method), "@AggregateSaver");
                }
            }
            if (!found) {
                throw new IllegalArgumentException("Repository " + host.getClass().getName()
                        + " declares no @AggregateLoader or @AggregateSaver method");
            }
        }
        return new HarnessAggregateAccess(Map.copyOf(loaders), Map.copyOf(savers), resolver);
    }

    /** {@code true} if aggregates of {@code type} can be loaded: a client loader, or a supporting resolver. */
    boolean canLoad(Class<?> type) {
        return loaders.containsKey(type) || resolver.supports(type);
    }

    /**
     * Loads the aggregate ({@code type}, {@code id}). Loader and resolver exceptions propagate unchanged.
     *
     * @throws AggregateNotFoundException if the loader returns {@code null} or the resolver finds nothing
     * @throws IllegalStateException      if there is no way to load {@code type}, or the resolver returned an
     *                                    object of another type
     */
    Object load(Class<?> type, Object id) {
        HostMethod loader = loaders.get(type);
        if (loader != null) {
            Object aggregate = HarnessReflection.invoke(loader.method(), loader.host(), id);
            if (aggregate == null) {
                throw new AggregateNotFoundException(type, id);
            }
            return aggregate;
        }
        if (!resolver.supports(type)) {
            throw new IllegalStateException("No @AggregateLoader and no supporting resolver for " + type.getName());
        }
        Object aggregate = resolver.load(type, id).orElseThrow(() -> new AggregateNotFoundException(type, id));
        if (!type.isInstance(aggregate)) {
            throw new IllegalStateException("Resolver returned " + aggregate.getClass().getName() + " for "
                    + type.getName() + " '" + id + "'");
        }
        return aggregate;
    }

    /** Saves {@code aggregate} as {@code type}: client saver, else supporting resolver, else nothing. */
    void save(Class<?> type, Object aggregate) {
        HostMethod saver = savers.get(type);
        if (saver != null) {
            HarnessReflection.invoke(saver.method(), saver.host(), aggregate);
        } else if (resolver.supports(type)) {
            resolver.save(aggregate);
        }
    }

    private static void put(Map<Class<?>, HostMethod> map, Class<?> type, HostMethod candidate, String kind) {
        HostMethod existing = map.putIfAbsent(type, candidate);
        if (existing != null) {
            throw new IllegalArgumentException(type.getName() + " has two " + kind + " methods: "
                    + existing.method() + " and " + candidate.method());
        }
    }
}
