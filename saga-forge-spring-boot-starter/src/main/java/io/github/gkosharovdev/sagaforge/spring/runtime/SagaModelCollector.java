package io.github.gkosharovdev.sagaforge.spring.runtime;

import io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
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

/**
 * Collects the classes the {@code SagaRuntime} is assembled from ({@code
 * specs/008-spring-boot-starter} FR-002, FR-004, research §1): sagas, aggregates and the hosts of
 * command handlers, loaders and savers, found in the application's auto-configuration packages and
 * {@code saga-forge.scan-packages}, among the application's beans, and from {@link
 * SagaModelContributor} beans. Events and commands are reached from signatures and are not
 * collected. Aggregates and command handlers are recognised in every vocabulary of the {@link
 * DomainTypes} ({@code specs/016-marker-spi-jmolecules}).
 */
public final class SagaModelCollector {

  private final ListableBeanFactory beanFactory;
  private final List<String> extraPackages;
  private final ObjectProvider<SagaModelContributor> contributors;
  private final DomainTypes domainTypes;

  /** A collector recognising Saga Forge's annotations only. */
  public SagaModelCollector(
      ListableBeanFactory beanFactory,
      List<String> extraPackages,
      ObjectProvider<SagaModelContributor> contributors) {
    this(beanFactory, extraPackages, contributors, DomainTypes.builtIn());
  }

  /**
   * @param domainTypes the vocabularies that mark aggregates and command handlers
   */
  public SagaModelCollector(
      ListableBeanFactory beanFactory,
      List<String> extraPackages,
      ObjectProvider<SagaModelContributor> contributors,
      DomainTypes domainTypes) {
    this.beanFactory = beanFactory;
    this.extraPackages = List.copyOf(extraPackages);
    this.contributors = contributors;
    this.domainTypes = domainTypes;
  }

  /**
   * @return the model: sagas, then handler/loader/saver hosts, then aggregates, each sorted by
   *     class name
   */
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
    ordered.sort(Comparator.comparingInt(this::rank).thenComparing(Class::getName));
    return List.copyOf(ordered);
  }

  private int rank(Class<?> type) {
    if (type.isAnnotationPresent(Saga.class)) {
      return 0;
    }
    return domainTypes.isAggregate(type) ? 2 : 1;
  }

  /**
   * Whether {@code type} belongs to the model: a saga, an aggregate, or the host of a command
   * handler, loader or saver.
   */
  boolean isModelClass(Class<?> type) {
    if (type.isAnnotationPresent(Saga.class) || domainTypes.isAggregate(type)) {
      return true;
    }
    for (var method : type.getDeclaredMethods()) {
      if (domainTypes.commandHandler(method).isPresent()
          || method.isAnnotationPresent(AggregateLoader.class)
          || method.isAnnotationPresent(AggregateSaver.class)) {
        return true;
      }
    }
    return false;
  }

  private List<Class<?>> scan(String basePackage) {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(
              org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
            return definition.getMetadata().isIndependent();
          }
        };
    ClassLoader loader = classLoader();
    scanner.addIncludeFilter(new SagaTypeFilter());
    scanner.addIncludeFilter(new DomainTypeFilter(loader));
    List<Class<?>> classes = new ArrayList<>();
    for (var candidate : scanner.findCandidateComponents(basePackage)) {
      try {
        classes.add(ClassUtils.forName(candidate.getBeanClassName(), loader));
      } catch (ClassNotFoundException | LinkageError e) {
        throw new SagaForgeConfigurationException(
            "Saga model class "
                + candidate.getBeanClassName()
                + " found in package "
                + basePackage
                + " cannot be loaded: "
                + e.getMessage(),
            "Check the class and its dependencies, or exclude its package from saga-forge.scan-packages.");
      }
    }
    return classes;
  }

  private ClassLoader classLoader() {
    return beanFactory instanceof org.springframework.beans.factory.config.ConfigurableBeanFactory c
            && c.getBeanClassLoader() != null
        ? c.getBeanClassLoader()
        : ClassUtils.getDefaultClassLoader();
  }

  /** Matches sagas and classes with loader or saver methods, from metadata only. */
  private static final class SagaTypeFilter implements TypeFilter {

    private static final List<String> METHOD_MARKERS =
        List.of(AggregateLoader.class.getName(), AggregateSaver.class.getName());

    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
      AnnotationMetadata metadata = reader.getAnnotationMetadata();
      return metadata.hasAnnotation(Saga.class.getName())
          || METHOD_MARKERS.stream().anyMatch(metadata::hasAnnotatedMethods);
    }
  }

  /**
   * Matches aggregates and command handler hosts in every vocabulary, which metadata cannot tell (a
   * vocabulary may mark by interface): loads the class, without initialising it, and asks {@link
   * DomainTypes}. A class that cannot be loaded is not a candidate (memory.md G-016-01).
   */
  private final class DomainTypeFilter implements TypeFilter {

    private final ClassLoader loader;

    private DomainTypeFilter(ClassLoader loader) {
      this.loader = loader;
    }

    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
      if (reader.getClassMetadata().isAnnotation() || reader.getClassMetadata().isInterface()) {
        return false;
      }
      try {
        return isModelClass(ClassUtils.forName(reader.getClassMetadata().getClassName(), loader));
      } catch (ClassNotFoundException | LinkageError unloadable) {
        return false;
      }
    }
  }
}
