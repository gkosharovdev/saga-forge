package io.github.gkosharovdev.sagaforge.spring.modulith;

import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.StartDeadline;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.spring.SpringTransactionConfiguration;
import io.github.gkosharovdev.sagaforge.spring.runtime.SagaModelCollector;
import io.github.gkosharovdev.sagaforge.spring.storage.PostgresStorageConfiguration;
import io.github.gkosharovdev.sagaforge.storage.postgres.PostgresMessageStore;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;

/**
 * Spring Modulith interop ({@code specs/007-in-memory-transport} FR-028): when the event
 * publication registry is present, the transport's {@link MessageStore} is a {@link
 * ModulithMessageStore}, so the application has one outbox. Switched off with {@code
 * saga-forge.transport.modulith-interop=false}; when the property is not set and the saga model
 * declares a deadline, the transport keeps Saga Forge's own log ({@code specs/014-publish-once}).
 */
@AutoConfiguration(
    before = PostgresStorageConfiguration.class,
    after = SpringTransactionConfiguration.class,
    afterName = {
      "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration",
      "org.springframework.modulith.events.config.EventPublicationAutoConfiguration",
      "org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration",
      "org.springframework.modulith.events.jackson2.Jackson2EventSerializationConfiguration"
    })
@ConditionalOnClass({
  EventPublicationRepository.class,
  InMemoryTransport.class,
  PostgresMessageStore.class
})
@ConditionalOnBean({
  EventPublicationRepository.class,
  EventSerializer.class,
  DataSource.class,
  TransactionBoundary.class
})
@ConditionalOnProperty(
    prefix = "saga-forge.transport",
    name = "modulith-interop",
    havingValue = "true",
    matchIfMissing = true)
public class ModulithInteropConfiguration {

  static final String INTEROP_PROPERTY = "saga-forge.transport.modulith-interop";

  private static final System.Logger LOGGER =
      System.getLogger("io.github.gkosharovdev.sagaforge.transport");

  /**
   * The registry-backed store; or, when {@code saga-forge.transport.modulith-interop} is not set
   * and the saga model declares a deadline, Saga Forge's own log, since the registry cannot hold a
   * message until a due time ({@code specs/014-publish-once} D9, contracts/starter-configuration.md
   * DI1 to DI3).
   */
  @Bean
  @ConditionalOnMissingBean(MessageStore.class)
  public MessageStore sagaForgeMessageStore(
      EventPublicationRepository registry,
      EventSerializer serializer,
      DataSource dataSource,
      TransactionBoundary transactions,
      Environment environment,
      ObjectProvider<Clock> clock,
      ObjectProvider<SagaModelCollector> model) {
    ClassLoader classLoader = ModulithInteropConfiguration.class.getClassLoader();
    if (!environment.containsProperty(INTEROP_PROPERTY)) {
      List<String> withDeadlines = sagasWithDeadlines(model);
      if (!withDeadlines.isEmpty()) {
        LOGGER.log(
            Level.INFO,
            "saga.transport.modulithInterop.skipped reason=deadlines sagas="
                + String.join(",", withDeadlines)
                + "; Saga Forge keeps its own message log (set "
                + INTEROP_PROPERTY
                + " to decide explicitly)");
        return new PostgresMessageStore(dataSource, transactions, classLoader);
      }
    }
    if (environment.getProperty(
        "spring.modulith.events.republish-outstanding-events-on-restart", Boolean.class, false)) {
      LOGGER.log(
          Level.INFO,
          "saga.transport.modulithInterop republish-outstanding-events-on-restart is on;"
              + " Saga Forge messages are excluded (no listener for "
              + ModulithMessageStore.TARGET
              + ")");
    }
    return new ModulithMessageStore(
        registry,
        serializer,
        new PostgresMessageStore(dataSource, transactions, classLoader),
        classLoader,
        clock.getIfUnique(Clock::systemUTC));
  }

  /**
   * @return the names of the collected saga classes that declare a {@code @StartDeadline}, sorted
   */
  static List<String> sagasWithDeadlines(ObjectProvider<SagaModelCollector> model) {
    SagaModelCollector collector = model.getIfAvailable();
    if (collector == null) {
      return List.of();
    }
    return collector.collect().stream()
        .filter(type -> type.isAnnotationPresent(Saga.class))
        .filter(ModulithInteropConfiguration::declaresDeadline)
        .map(Class::getName)
        .sorted()
        .toList();
  }

  private static boolean declaresDeadline(Class<?> sagaType) {
    return Arrays.stream(sagaType.getDeclaredMethods())
        .anyMatch(method -> method.getAnnotationsByType(StartDeadline.class).length > 0);
  }
}
