package io.github.gkosharovdev.sagaforge.spring.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.MetadataAccessor;
import io.github.gkosharovdev.sagaforge.core.spi.ThreadLocalMetadataAccessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The starter's metadata wiring ({@code specs/019-process-metadata} T032). */
class SagaForgeMetadataConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SagaForgeMetadataConfiguration.class));

  @Test
  @DisplayName("without saga-forge.metadata.mdc-keys there is no accessor")
  void noKeys() {
    runner.run(context -> assertThat(context).doesNotHaveBean(MetadataAccessor.class));
  }

  @Test
  @DisplayName("with keys, an MDC accessor for exactly those keys")
  void mdcAccessor() {
    runner
        .withPropertyValues("saga-forge.metadata.mdc-keys=correlationId,tenant")
        .run(
            context -> {
              assertThat(context).hasSingleBean(MetadataAccessor.class);
              assertThat(context.getBean(MetadataAccessor.class))
                  .isInstanceOfSatisfying(
                      MdcMetadataAccessor.class,
                      mdc -> assertThat(mdc.keys()).containsExactly("correlationId", "tenant"));
            });
  }

  @Test
  @DisplayName("an application MetadataAccessor bean wins over the MDC accessor")
  void applicationAccessorWins() {
    ThreadLocalMetadataAccessor own = new ThreadLocalMetadataAccessor();
    runner
        .withPropertyValues("saga-forge.metadata.mdc-keys=correlationId")
        .withBean(MetadataAccessor.class, () -> own)
        .run(context -> assertThat(context.getBean(MetadataAccessor.class)).isSameAs(own));
  }

  @Test
  @DisplayName("without SLF4J, the keys are ignored and no accessor is registered")
  void noSlf4j() {
    runner
        .withClassLoader(new FilteredClassLoader("org.slf4j"))
        .withPropertyValues("saga-forge.metadata.mdc-keys=correlationId")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(MetadataAccessor.class);
              assertThat(context).hasBean("sagaForgeMdcKeysIgnored");
            });
  }

  @Test
  @DisplayName("saga-forge.enabled=false registers nothing")
  void disabled() {
    runner
        .withPropertyValues("saga-forge.enabled=false", "saga-forge.metadata.mdc-keys=a")
        .run(context -> assertThat(context).doesNotHaveBean(MetadataAccessor.class));
  }
}
