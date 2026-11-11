package io.github.gkosharovdev.sagaforge.spring.runtime;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.ClassUtils;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;

/**
 * Collects the classes the {@code SagaRuntime} is assembled from ({@code specs/008-spring-boot-starter} FR-002,
 * FR-004, research §1): sagas, aggregates and the hosts of command handlers, loaders and savers, found in the
 * application's auto-configuration packages and {@code saga-forge.scan-packages}, among the application's beans, and
 * from {@link SagaModelContributor} beans. Events and commands are reached from signatures and are not collected.
 */
public final class SagaModelCollector {

    private final ListableBeanFactory beanFactory;
    private final List<String> extraPackages;
    private final ObjectProvider<SagaModelContributor> contributors;

    public SagaModelCollector(ListableBeanFactory beanFactory, List<String> extraPackages,
            ObjectProvider<SagaModelContributor> contributors) {
        this.beanFactory = beanFactory;
        this.extraPackages = List.copyOf(extraPackages);
        this.contributors = contributors;
    }

    /** @return the model: sagas, then handler/loader/saver hosts, then aggregates, each sorted by class name */
    public List<Class<?>> collect() {
        Set<Class<?>> found = new LinkedHashSet<>();
        Set<String> packages = new LinkedHashSet<>(extraPackages);
        if (AutoConfigurationPackages.has(beanFactory)) {
            packages.addAll(AutoConfigurationPackages.get(beanFactory));
        }
        packages.forEach(name -> found.addAll(scan(name)));
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(beanName, false);
            if (type != null && isModelClass(ClassUtils.getUserClass(type))) {
                found.add(ClassUtils.getUserClass(type));
            }
        }
        contributors.orderedStream().forEach(contributor -> found.addAll(contributor.classes()));
        List<Class<?>> ordered = new ArrayList<>(found);
        ordered.sort(Comparator.comparingInt(SagaModelCollector::rank).thenComparing(Class::getName));
        return List.copyOf(ordered);
    }

    private static int rank(Class<?> type) {
        if (type.isAnnotationPresent(Saga.class)) {
            return 0;
        }
        return type.isAnnotationPresent(Aggregate.class) ? 2 : 1;
    }

    static boolean isModelClass(Class<?> type) {
        if (type.isAnnotationPresent(Saga.class) || type.isAnnotationPresent(Aggregate.class)) {
            return true;
        }
        for (var method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(CommandHandler.class) || method.isAnnotationPresent(AggregateLoader.class)
                    || method.isAnnotationPresent(AggregateSaver.class)) {
                return true;
            }
        }
        return false;
    }

    private List<Class<?>> scan(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new ModelTypeFilter());
        ClassLoader loader = beanFactory instanceof org.springframework.beans.factory.config.ConfigurableBeanFactory c
                && c.getBeanClassLoader() != null ? c.getBeanClassLoader() : ClassUtils.getDefaultClassLoader();
        List<Class<?>> classes = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(basePackage)) {
            try {
                classes.add(ClassUtils.forName(candidate.getBeanClassName(), loader));
            } catch (ClassNotFoundException | LinkageError e) {
                throw new SagaForgeConfigurationException("Saga model class " + candidate.getBeanClassName()
                        + " found in package " + basePackage + " cannot be loaded: " + e.getMessage(),
                        "Check the class and its dependencies, or exclude its package from saga-forge.scan-packages.");
            }
        }
        return classes;
    }

    /** Matches sagas, aggregates and classes with handler, loader or saver methods, from metadata only. */
    private static final class ModelTypeFilter implements TypeFilter {

        private static final List<Class<? extends Annotation>> TYPE_MARKERS = List.of(Saga.class, Aggregate.class);
        private static final List<Class<? extends Annotation>> METHOD_MARKERS = List.of(CommandHandler.class,
                AggregateLoader.class, AggregateSaver.class);

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            AnnotationMetadata metadata = reader.getAnnotationMetadata();
            return TYPE_MARKERS.stream().anyMatch(marker -> metadata.hasAnnotation(marker.getName()))
                    || METHOD_MARKERS.stream().anyMatch(marker -> metadata.hasAnnotatedMethods(marker.getName()));
        }
    }
}
