package io.github.gkosharovdev.sagaforge.spring.host;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.MethodParameter;

import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;

/**
 * Handler parameters other than the command and {@code CommandId} are resolved like any Spring injection point
 * ({@code specs/008-spring-boot-starter} FR-012): {@code @Qualifier}, {@code @Lazy}, {@code Optional},
 * {@code ObjectProvider} and collections are honoured. A parameter with no or several candidate beans is unsupported,
 * so the runtime's startup report names it.
 */
public final class SpringHandlerParameterResolver implements HandlerParameterResolver {

    private final AutowireCapableBeanFactory beanFactory;
    private final Map<Parameter, Boolean> supported = new ConcurrentHashMap<>();

    public SpringHandlerParameterResolver(AutowireCapableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public boolean supports(Executable handler, Parameter parameter) {
        return supported.computeIfAbsent(parameter, p -> {
            try {
                return resolve(handler, p) != null;
            } catch (BeansException unresolvable) {
                return false;
            }
        });
    }

    @Override
    public Object resolve(Executable handler, Parameter parameter) {
        int index = List.of(handler.getParameters()).indexOf(parameter);
        DependencyDescriptor descriptor = new DependencyDescriptor(MethodParameter.forExecutable(handler, index), true);
        return beanFactory.resolveDependency(descriptor, null);
    }
}
