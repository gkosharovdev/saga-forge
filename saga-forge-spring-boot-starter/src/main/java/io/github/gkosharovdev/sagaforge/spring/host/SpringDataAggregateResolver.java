package io.github.gkosharovdev.sagaforge.spring.host;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.model.IdentifierMember;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeConfigurationException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.repository.support.DefaultRepositoryInvokerFactory;
import org.springframework.data.repository.support.Repositories;
import org.springframework.data.repository.support.RepositoryInvokerFactory;
import org.springframework.util.ClassUtils;

/**
 * Loads and saves aggregates through their Spring Data repositories ({@code
 * specs/008-spring-boot-starter} FR-013, research §4): {@code findById} with the identifier
 * converted to the repository's id type, and {@code save}, in the unit of work's transaction.
 * Client {@code @AggregateLoader}/{@code @AggregateSaver} methods keep precedence (the runtime
 * consults them first). Works for any Spring Data module, and for aggregates of any vocabulary of
 * the {@link DomainTypes} ({@code specs/016-marker-spi-jmolecules}).
 */
public final class SpringDataAggregateResolver implements AggregateResolver {

  private final ListableBeanFactory beanFactory;
  private final ConversionService conversionService;
  private final DomainTypes domainTypes;
  private volatile Repositories repositories;
  private volatile RepositoryInvokerFactory invokers;

  /** A resolver for aggregates marked with Saga Forge's annotations. */
  public SpringDataAggregateResolver(
      ListableBeanFactory beanFactory, ConversionService conversionService) {
    this(beanFactory, conversionService, DomainTypes.builtIn());
  }

  /**
   * @param domainTypes the vocabularies naming each aggregate's identifier
   */
  public SpringDataAggregateResolver(
      ListableBeanFactory beanFactory,
      ConversionService conversionService,
      DomainTypes domainTypes) {
    this.beanFactory = beanFactory;
    this.conversionService = conversionService;
    this.domainTypes = domainTypes;
  }

  @Override
  public boolean supports(Class<?> aggregateType) {
    return repositories().hasRepositoryFor(aggregateType);
  }

  @Override
  public Optional<Object> load(Class<?> aggregateType, Object aggregateId) {
    return invokers()
        .getInvokerFor(aggregateType)
        .invokeFindById(aggregateId)
        .map(Object.class::cast);
  }

  @Override
  public void save(Object aggregate) {
    invokers().getInvokerFor(ClassUtils.getUserClass(aggregate)).invokeSave(aggregate);
  }

  /**
   * Fails if the type of the aggregate's identifier cannot be converted to its repository's id
   * type.
   *
   * @throws SagaForgeConfigurationException naming the aggregate and both types
   */
  public void verify(Class<?> aggregateType) {
    if (!supports(aggregateType)) {
      return;
    }
    Class<?> idType =
        repositories()
            .getRepositoryInformationFor(aggregateType)
            .map(info -> info.getIdType())
            .orElse(null);
    Class<?> identifierType = identifierType(aggregateType);
    if (idType != null
        && identifierType != null
        && !idType.isAssignableFrom(identifierType)
        && !conversionService.canConvert(identifierType, idType)) {
      throw new SagaForgeConfigurationException(
          "Aggregate "
              + aggregateType.getName()
              + " has an "
              + identifierLabel(aggregateType)
              + " of type "
              + identifierType.getName()
              + ", but its Spring Data repository uses id type "
              + idType.getName()
              + " and no conversion exists.",
          "Use the same type for the "
              + identifierLabel(aggregateType)
              + " and the repository id, or register a converter.");
    }
  }

  /** The type of the aggregate's first identifier, in its class or a superclass; null if none. */
  private Class<?> identifierType(Class<?> type) {
    for (Class<?> current = type;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      List<IdentifierMember> identifiers = domainTypes.identifiers(current);
      if (!identifiers.isEmpty()) {
        return identifiers.get(0).valueType();
      }
    }
    return null;
  }

  /**
   * How the texts name the identifier: {@code "@AggregateIdentifier"} (Saga Forge's text before
   * vocabularies), else its marker and member.
   */
  private String identifierLabel(Class<?> type) {
    if (domainTypes.vocabularies().size() == 1) {
      return "@AggregateIdentifier";
    }
    return domainTypes.describeIdentifier(type);
  }

  private Repositories repositories() {
    Repositories current = repositories;
    if (current == null) {
      current = new Repositories(beanFactory);
      repositories = current;
    }
    return current;
  }

  private RepositoryInvokerFactory invokers() {
    RepositoryInvokerFactory current = invokers;
    if (current == null) {
      current = new DefaultRepositoryInvokerFactory(repositories(), conversionService);
      invokers = current;
    }
    return current;
  }
}
