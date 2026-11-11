package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageQuery;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStatus;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.RecordingReceiver;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;

/**
 * Engine + {@link InMemoryTransport} over PostgreSQL: {@link PostgresStorageProvider}, {@link PostgresSagaContextStore}
 * and {@link PostgresMessageStore} on one database (specs/007-in-memory-transport tasks.md T049). Each
 * {@link #assemble()} opens a new {@link DataSource}, as a restarted process would.
 */
final class PostgresTransportHarness implements AutoCloseable {

    final PaymentService payments = new PaymentService();
    final ShippingService shipping = new ShippingService();
    final CardService cards = new CardService();

    private final Class<?>[] model;
    private final TransportSettings settings;
    private MessageStore logOverride;
    private DataSource dataSource;
    private JdbcTransactionBoundary boundary;
    private PostgresMessageStore log;
    private SagaEngine engine;
    private InMemoryTransport transport;
    private RecordingReceiver receiver;

    PostgresTransportHarness(TransportSettings settings, Class<?>... model) {
        this.settings = settings;
        this.model = model;
        assemble();
    }

    /** Builds a new engine and transport (not started) on a new DataSource. */
    PostgresTransportHarness assemble() {
        dataSource = PostgresTestSupport.newDataSource();
        boundary = new JdbcTransactionBoundary(dataSource);
        log = new PostgresMessageStore(dataSource, boundary);
        MessageStore used = logOverride != null ? logOverride : log;
        transport = InMemoryTransport.builder().messageStore(used).transactions(boundary).settings(settings).build();
        engine = SagaEngine.builder()
                .register(model)
                .storage(new PostgresStorageProvider(dataSource, boundary))
                .contextStore(new PostgresSagaContextStore(dataSource))
                .transactions(boundary)
                .dispatcher(transport.dispatcher())
                .outbox(transport.outbox())
                .handlerInstances(new MapHandlerInstanceProvider().register(payments).register(shipping)
                        .register(cards))
                .build();
        engine.start();
        receiver = new RecordingReceiver(engine::receive);
        return this;
    }

    /** Uses {@code override} instead of the plain Postgres log from the next {@link #assemble()} on. */
    PostgresTransportHarness withLog(java.util.function.Function<PostgresMessageStore, MessageStore> override) {
        this.logOverride = override.apply(new PostgresMessageStore(PostgresTestSupport.newDataSource(),
                new JdbcTransactionBoundary(PostgresTestSupport.newDataSource())));
        return this;
    }

    PostgresTransportHarness start() {
        transport.start(receiver, engine.commandReceiver());
        return this;
    }

    PostgresTransportHarness restart(Duration grace) {
        transport.stop(grace);
        assemble();
        return start();
    }

    PostgresMessageStore log() {
        return log;
    }

    InMemoryTransport transport() {
        return transport;
    }

    RecordingReceiver receiver() {
        return receiver;
    }

    void publish(Object event) {
        engine.eventPublisher().publish(event);
    }

    List<StoredMessage> messages() {
        return log.query(MessageQuery.builder().limit(MessageQuery.MAX_LIMIT).build());
    }

    List<StoredMessage> messagesOf(Class<?> type) {
        return log.query(MessageQuery.builder().payloadType(type.getName()).limit(MessageQuery.MAX_LIMIT).build());
    }

    /** @return the saga instances as {@code type simple name -> status} rows */
    static List<Map<String, Object>> instances(Class<?> sagaType) {
        return PostgresTestSupport.queryRows("SELECT saga_id, status FROM saga_instance WHERE saga_type = ?",
                sagaType.getName());
    }

    static boolean hasInstance(Class<?> sagaType, String status) {
        return instances(sagaType).stream().anyMatch(row -> status.equals(row.get("status")));
    }

    void awaitQuiet(Duration timeout) {
        TransportHarness.await(() -> {
            List<StoredMessage> all = messages();
            return !all.isEmpty() && all.stream().allMatch(m -> m.status() != MessageStatus.UNCONSUMED);
        }, timeout, "the message store to become quiet");
    }

    @Override
    public void close() {
        transport.stop(Duration.ofSeconds(5));
    }
}
