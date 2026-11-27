package io.github.gkosharovdev.sagaforge.spring.modulith;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.spring.runtime.SagaModelCollector;
import io.github.gkosharovdev.sagaforge.spring.runtime.SagaModelContributor;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageStore;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;

/**
 * Which message store Spring Modulith interop gives the transport when the model declares a
 * deadline (specs/014-publish-once D9, contracts/starter-configuration.md DI1 to DI3), over stub
 * registry beans: no database is touched.
 */
class DeadlineInteropDefaultTest {

  private ApplicationContextRunner runner(Class<?>... model) {
    DataSource dataSource = new org.postgresql.ds.PGSimpleDataSource();
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ModulithInteropConfiguration.class))
        .withBean(DataSource.class, () -> dataSource)
        .withBean(TransactionBoundary.class, InMemoryTransactionBoundary::new)
        .withBean(EventPublicationRepository.class, () -> stub(EventPublicationRepository.class))
        .withBean(EventSerializer.class, () -> stub(EventSerializer.class))
        .withBean(SagaModelContributor.class, () -> () -> List.of(model))
        .withUserConfiguration(CollectorConfig.class);
  }

  @Test
  @DisplayName("DI1: property not set, a saga declares a deadline → Saga Forge's own log")
  void deadlinesKeepOwnLog() {
    runner(WithDeadline.class)
        .run(
            context ->
                assertThat(context.getBean(MessageStore.class))
                    .isInstanceOf(PostgresMessageStore.class));
  }

  @Test
  @DisplayName("DI2: explicitly true with a deadline → the registry, so startup refuses later")
  void explicitTrueKeepsInterop() {
    runner(WithDeadline.class)
        .withPropertyValues("saga-forge.transport.modulith-interop=true")
        .run(
            context ->
                assertThat(context.getBean(MessageStore.class))
                    .isInstanceOf(ModulithMessageStore.class));
  }

  @Test
  @DisplayName("DI3: property not set, no deadline → the registry, as before")
  void noDeadlineKeepsInterop() {
    runner(WithoutDeadline.class)
        .run(
            context ->
                assertThat(context.getBean(MessageStore.class))
                    .isInstanceOf(ModulithMessageStore.class));
  }

  /** Only what the detection reads: a saga class with a {@code @StartDeadline} step. */
  @Saga
  static class WithDeadline {

    @SagaStep(associationProperty = "id")
    @StartDeadline(name = "lapse", duration = "PT1H")
    void start(Object event) {}
  }

  @Saga
  static class WithoutDeadline {

    @SagaStep(associationProperty = "id")
    void start(Object event) {}
  }

  /** The starter's collector, over the contributed model. */
  @Configuration(proxyBeanMethods = false)
  static class CollectorConfig {

    @Bean
    SagaModelCollector sagaForgeModelCollector(
        ConfigurableListableBeanFactory beanFactory,
        ObjectProvider<SagaModelContributor> contributors) {
      return new SagaModelCollector(beanFactory, List.of(), contributors);
    }
  }

  private static <T> T stub(Class<T> type) {
    return type.cast(
        java.lang.reflect.Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "toString" -> "stub " + type.getSimpleName();
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> null;
                }));
  }
}
