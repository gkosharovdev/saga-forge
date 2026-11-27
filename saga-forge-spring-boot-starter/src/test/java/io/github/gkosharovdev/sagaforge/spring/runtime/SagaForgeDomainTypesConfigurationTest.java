package io.github.gkosharovdev.sagaforge.spring.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypeResolver;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestDomainTypeResolver;
import io.github.gkosharovdev.sagaforge.jmolecules.JMoleculesDomainTypeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The starter's {@link DomainTypes} bean (specs/016-marker-spi-jmolecules T033, FR-029, FR-030).
 */
class SagaForgeDomainTypesConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SagaForgeDomainTypesConfiguration.class));

  @Test
  @DisplayName("with saga-forge-jmolecules on the class path, jMolecules is enabled")
  void enabledByDefault() {
    runner.run(
        context ->
            assertThat(context.getBean(DomainTypes.class).vocabularies())
                .containsExactly(DomainTypes.builtIn().vocabularies().get(0), "jMolecules"));
  }

  @Test
  @DisplayName("saga-forge.jmolecules.enabled=false leaves Saga Forge's annotations only")
  void disabledByProperty() {
    runner
        .withPropertyValues("saga-forge.jmolecules.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(JMoleculesDomainTypeResolver.class);
              assertThat(context.getBean(DomainTypes.class).vocabularies())
                  .isEqualTo(DomainTypes.builtIn().vocabularies());
            });
  }

  @Test
  @DisplayName("without the module, Saga Forge's annotations only")
  void moduleAbsent() {
    runner
        .withClassLoader(new FilteredClassLoader(JMoleculesDomainTypeResolver.class))
        .run(
            context ->
                assertThat(context.getBean(DomainTypes.class).vocabularies())
                    .isEqualTo(DomainTypes.builtIn().vocabularies()));
  }

  @Test
  @DisplayName("an application DomainTypeResolver bean is added")
  void applicationResolver() {
    runner
        .withPropertyValues("saga-forge.jmolecules.enabled=false")
        .withBean(DomainTypeResolver.class, TestDomainTypeResolver::new)
        .run(
            context ->
                assertThat(context.getBean(DomainTypes.class).vocabularies())
                    .endsWith(TestDomainTypeResolver.NAME));
  }

  @Test
  @DisplayName("an application DomainTypes bean wins")
  void applicationDomainTypes() {
    DomainTypes own = DomainTypes.of(new TestDomainTypeResolver());
    runner
        .withBean(DomainTypes.class, () -> own)
        .run(context -> assertThat(context.getBean(DomainTypes.class)).isSameAs(own));
  }
}
