package io.github.gkosharovdev.sagaforge.spring.runtime;

import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypeResolver;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.jmolecules.JMoleculesDomainTypeResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's vocabularies of domain markers ({@code specs/016-marker-spi-jmolecules},
 * contracts/host-configuration.md): one {@link DomainTypes} bean, Saga Forge's annotations first,
 * then every {@link DomainTypeResolver} bean in {@code @Order} order, including jMolecules' when
 * saga-forge-jmolecules is on the class path and {@code saga-forge.jmolecules.enabled} is not
 * {@code false}. The runtime, the transport, the model collector and the Spring Data resolver all
 * use it. An application {@code DomainTypes} bean replaces it.
 */
@AutoConfiguration
@ConditionalOnClass(DomainTypes.class)
public class SagaForgeDomainTypesConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DomainTypes sagaForgeDomainTypes(ObjectProvider<DomainTypeResolver> resolvers) {
    return DomainTypes.of(resolvers.orderedStream().toArray(DomainTypeResolver[]::new));
  }

  /** jMolecules' markers, when the module is present and not switched off. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = "io.github.gkosharovdev.sagaforge.jmolecules.JMoleculesDomainTypeResolver")
  @ConditionalOnProperty(
      prefix = "saga-forge.jmolecules",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class JMoleculesVocabulary {

    @Bean
    @ConditionalOnMissingBean
    public JMoleculesDomainTypeResolver sagaForgeJMoleculesDomainTypeResolver() {
      return new JMoleculesDomainTypeResolver();
    }
  }
}
