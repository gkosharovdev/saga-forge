package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeasedMessages;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.RecordingLogger;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.RelayRole;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One application instance on the shared test database ({@code specs/013-active-standby-relay}): a
 * {@link SagaRuntime} and an {@link InMemoryTransport} over a {@link CuttableDataSource} of its
 * own, with its own services, recording every event its relay delivered.
 */
final class Replica {

  final CuttableDataSource dataSource = new CuttableDataSource(PostgresTestSupport.newDataSource());
  final CardService cards = new CardService();
  final RecordingLogger runtimeLog = new RecordingLogger();
  final List<Object> delivered = new CopyOnWriteArrayList<>();

  /** When each {@code lease} call that returned messages started. */
  final List<Instant> leasesReturningMessages = new CopyOnWriteArrayList<>();

  private final InMemoryTransport transport;
  private final SagaRuntime runtime;

  /**
   * @param properties resolves {@code ${key:default}} deadline durations
   */
  Replica(TransportSettings settings, Map<String, String> properties, Class<?>... model) {
    JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(dataSource);
    transport =
        InMemoryTransport.builder()
            .messageStore(recordingLeases(new PostgresMessageStore(dataSource, boundary)))
            .transactions(boundary)
            .settings(settings)
            .build();
    runtime =
        SagaRuntime.builder()
            .register(model)
            .storage(new PostgresStorageProvider(dataSource, boundary))
            .contextStore(new PostgresSagaContextStore(dataSource))
            .transactions(boundary)
            .dispatcher(transport.dispatcher())
            .outbox(transport.outbox())
            .handlerInstances(new MapHandlerInstanceProvider().register(cards))
            .propertyResolver(key -> Optional.ofNullable(properties.get(key)))
            .logger(runtimeLog)
            .build();
    runtime.start();
    transport.start(
        event -> {
          delivered.add(event);
          return runtime.receive(event);
        },
        runtime.commandReceiver());
  }

  /** A spy that notes {@code lease} calls returning messages; everything else delegates. */
  private MessageStore recordingLeases(PostgresMessageStore store) {
    return (MessageStore)
        Proxy.newProxyInstance(
            MessageStore.class.getClassLoader(),
            new Class<?>[] {MessageStore.class},
            (proxy, method, args) -> {
              Instant started = Instant.now();
              Object result;
              try {
                result = method.invoke(store, args);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
              if (method.getName().equals("lease")
                  && !((LeasedMessages) result).readable().isEmpty()) {
                leasesReturningMessages.add(started);
              }
              return result;
            });
  }

  /** Appends {@code event} through this instance's dispatcher. */
  void publish(Object event) {
    transport.dispatcher().dispatch(event);
  }

  /** Hands {@code event} to this instance's runtime directly, as an in-process caller would. */
  void receive(Object event) {
    runtime.receive(event);
  }

  /**
   * @return the delivered events of {@code type}
   */
  <T> List<T> delivered(Class<T> type) {
    return delivered.stream().filter(type::isInstance).map(type::cast).toList();
  }

  RelayRole role() {
    return transport.leadership().role();
  }

  InMemoryTransport transport() {
    return transport;
  }

  void stop() {
    transport.stop(Duration.ofSeconds(10));
  }
}
