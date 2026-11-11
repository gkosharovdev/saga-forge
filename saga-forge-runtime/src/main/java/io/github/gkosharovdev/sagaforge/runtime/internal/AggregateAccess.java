package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How command routes load and save aggregates (002 contracts/command-gateway.md, FR-016, contracts/host-plugins.md).
 *
 * <ul>
 *   <li><b>Load</b>: the client {@code @AggregateLoader} for the exact aggregate type, else the first registered
 *       {@link AggregateResolver} that {@linkplain AggregateResolver#supports supports} it, else nothing.</li>
 *   <li><b>Save</b>: the client {@code @AggregateSaver} for the aggregate's class, else the first supporting
 *       resolver, else nothing (the client's own unit of work persists the change).</li>
 * </ul>
 *
 * <p>Loader and saver methods are discovered once, on the registered classes, keyed by aggregate type (the
 * scanner guarantees their shape and at most one of each per type). The object hosting them comes from the
 * {@link HandlerInstanceProvider} each time one is used, never at startup. Exceptions from loaders, savers,
 * resolvers and the provider leave unchanged; the command gateway fails the unit of work with them.</p>
 *
 * <p>Immutable; thread-safe if the provider, resolvers and client hosts are.</p>
 */
final class AggregateAccess {

    /** A loader or saver method and the class hosting it. */
    private record HostMethod(Class<?> host, Method method) {
    }

    private final Map<Class<?>, HostMethod> loaders;
    private final Map<Class<?>, HostMethod> savers;
    private final HandlerInstanceProvider instances;
    private final List<AggregateResolver> resolvers;

    private AggregateAccess(Map<Class<?>, HostMethod> loaders, Map<Class<?>, HostMethod> savers,
            HandlerInstanceProvider instances, List<AggregateResolver> resolvers) {
        this.loaders = Map.copyOf(loaders);
        this.savers = Map.copyOf(savers);
        this.instances = instances;
        this.resolvers = List.copyOf(resolvers);
    }

    /**
     * Discovers the loader and saver methods of {@code registered}.
     *
     * @param registered the classes registered with the engine
     * @param instances  supplies the objects hosting loader and saver methods
     * @param resolvers  host resolvers, in registration order
     */
    static AggregateAccess of(Collection<Class<?>> registered, HandlerInstanceProvider instances,
            List<AggregateResolver> resolvers) {
        Map<Class<?>, HostMethod> loaders = new LinkedHashMap<>();
        Map<Class<?>, HostMethod> savers = new LinkedHashMap<>();
        for (Class<?> host : registered) {
            Reflection.annotatedMethods(host, AggregateLoader.class)
                    .forEach(loader -> loaders.putIfAbsent(loader.getReturnType(), new HostMethod(host, loader)));
            Reflection.annotatedMethods(host, AggregateSaver.class)
                    .forEach(saver -> savers.putIfAbsent(saver.getParameterTypes()[0], new HostMethod(host, saver)));
        }
        return new AggregateAccess(loaders, savers, instances, resolvers);
    }

    /**
     * Loads the aggregate ({@code type}, {@code id}).
     *
     * @return the aggregate, or empty if the loader returned {@code null}, the resolver found nothing, or neither
     *         a loader nor a supporting resolver exists
     * @throws IllegalStateException if a resolver returned an object that is not a {@code type}
     */
    Optional<Object> load(Class<?> type, Object id) {
        HostMethod loader = loaders.get(type);
        if (loader != null) {
            return Optional.ofNullable(call(loader, id));
        }
        return resolverFor(type).flatMap(resolver -> resolver.load(type, id)).map(aggregate -> requireType(
                type, id, aggregate));
    }

    /** Saves {@code aggregate}: client saver, else supporting resolver, else nothing. */
    void save(Object aggregate) {
        Class<?> type = aggregate.getClass();
        HostMethod saver = savers.get(type);
        if (saver != null) {
            call(saver, aggregate);
            return;
        }
        resolverFor(type).ifPresent(resolver -> resolver.save(aggregate));
    }

    private Optional<AggregateResolver> resolverFor(Class<?> type) {
        return resolvers.stream().filter(resolver -> resolver.supports(type)).findFirst();
    }

    private Object call(HostMethod hostMethod, Object argument) {
        Method method = hostMethod.method();
        Object target = Modifier.isStatic(method.getModifiers()) ? null : instances.instanceOf(hostMethod.host());
        return Reflection.invoke(method, target, argument);
    }

    private static Object requireType(Class<?> type, Object id, Object aggregate) {
        if (!type.isInstance(aggregate)) {
            throw new IllegalStateException("AggregateResolver returned a " + aggregate.getClass().getName()
                    + " for " + type.getName() + " '" + id + "'");
        }
        return aggregate;
    }
}
