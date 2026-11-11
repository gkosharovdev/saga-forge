package io.github.gkosharovdev.sagaforge.spring;

import java.util.List;
import java.util.Map;


import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.acceptance.AcceptanceApp;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.MessageStoreQueries;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.PaymentService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.ShippingService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.FlakyReceiver;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportAcceptanceSuite;

/**
 * A Spring Boot application context hosting the acceptance model over Testcontainers Postgres
 * (specs/007-in-memory-transport SC-008): the starter assembles the runtime from {@link AcceptanceApp}'s beans and the
 * acceptance model ({@code specs/008-spring-boot-starter} SC-006). With {@code modulith} the Spring Modulith JDBC registry is active, otherwise its
 * auto-configurations are excluded. A restart closes the context and opens a new one; the services survive it.
 */
public final class SpringSetup implements TransportAcceptanceSuite.Setup {

    /** Services shared across restarts, as an external system would be. */
    public static final ThreadLocal<Services> SERVICES = new ThreadLocal<>();

    public record Services(PaymentService payments, ShippingService shipping, CardService cards, FlakyReceiver[] receiver) {
    }

    private final boolean modulith;
    private final Services services = new Services(new PaymentService(), new ShippingService(), new CardService(),
            new FlakyReceiver[1]);
    private ConfigurableApplicationContext context;

    public SpringSetup(boolean modulith) {
        this.modulith = modulith;
        StarterPostgres.reset();
        open();
    }

    private void open() {
        SERVICES.set(services);
        SpringApplicationBuilder builder = new SpringApplicationBuilder(AcceptanceApp.class).web(WebApplicationType.NONE)
                .properties(Map.of(
                        "saga-forge.transport.workers", "2",
                        "saga-forge.transport.max-attempts", "3",
                        "saga-forge.transport.initial-backoff", "10ms",
                        "saga-forge.transport.max-backoff", "40ms",
                        "saga-forge.transport.lease-duration", "1s",
                        "saga-forge.transport.poll-interval", "50ms",
                        "spring.modulith.events.jdbc.schema-initialization.enabled", "true",
                        "spring.autoconfigure.exclude", modulith ? ""
                                : "org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration,"
                                        + "org.springframework.modulith.events.config.EventPublicationAutoConfiguration,"
                                        + "org.springframework.modulith.events.config.EventExternalizationAutoConfiguration"));
        context = builder.run();
        SERVICES.remove();
    }

    public ConfigurableApplicationContext context() {
        return context;
    }

    @Override
    public void publish(Object event) {
        context.getBean(SagaRuntime.class).eventPublisher().publish(event);
    }

    @Override
    public boolean hasInstance(Class<?> sagaType, String status) {
        return !jdbc().queryForList("SELECT 1 FROM saga_instance WHERE saga_type = ? AND status = ?",
                sagaType.getName(), status).isEmpty();
    }

    @Override
    public int instanceCount(Class<?> sagaType) {
        return jdbc().queryForObject("SELECT count(*) FROM saga_instance WHERE saga_type = ?", Integer.class,
                sagaType.getName());
    }

    @Override
    public void restart() {
        context.close();
        open();
    }

    @Override
    public void failDeliveriesOf(Class<?> type, int times) {
        services.receiver()[0].fail(type, times);
    }

    @Override
    public MessageStoreQueries queries() {
        return context.getBean(InMemoryTransport.class).queries();
    }

    @Override
    public PaymentService payments() {
        return services.payments();
    }

    @Override
    public CardService cards() {
        return services.cards();
    }

    @Override
    public void close() {
        context.close();
    }

    public List<Map<String, Object>> registryRows() {
        return jdbc().queryForList("SELECT * FROM event_publication");
    }

    public JdbcTemplate jdbc() {
        return new JdbcTemplate(StarterPostgres.dataSource());
    }
}
