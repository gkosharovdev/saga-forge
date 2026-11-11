package io.github.gkosharovdev.sagaforge.spring.modulith;

import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.await;
import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.jdbc;
import static io.github.gkosharovdev.sagaforge.spring.support.StarterApp.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.modulith.events.IncompleteEventPublications;

import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import io.github.gkosharovdev.sagaforge.spring.StarterPostgres;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.ModulithApp;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.notifications.PaymentNotifier;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.orders.OrderPlaced;
import io.github.gkosharovdev.sagaforge.spring.modulith.app.orders.PaymentSaga;

/**
 * US5 scenarios 1 to 5 ({@code specs/008-spring-boot-starter}): a saga event reaches another module's
 * {@code @ApplicationModuleListener} with Spring Modulith's guarantees, over the JDBC registry.
 */
@Tag("container")
class ModuleListenerContainerTest {

    private ConfigurableApplicationContext context;

    @BeforeEach
    void start() {
        StarterPostgres.reset();
        context = new SpringApplicationBuilder(ModulithApp.class).web(WebApplicationType.NONE)
                .properties("spring.modulith.events.jdbc.schema-initialization.enabled=true",
                        "saga-forge.transport.poll-interval=50ms")
                .run();
    }

    @AfterEach
    void stop() {
        context.close();
    }

    private void place(String orderId, String amount) {
        context.getBean(EventPublisher.class).publish(new OrderPlaced(orderId, new BigDecimal(amount)));
    }

    private List<String> listenerPublications(String completion) {
        return jdbc().queryForList("SELECT listener_id FROM event_publication WHERE listener_id LIKE '%PaymentNotifier%'"
                + " AND completion_date IS " + completion, String.class);
    }

    @Test
    @DisplayName("one outbox: the transport's messages live in Spring Modulith's registry")
    void oneOutbox() {
        assertThat(context.getBean(MessageStore.class)).isInstanceOf(ModulithMessageStore.class);
    }

    @Test
    @DisplayName("the listener runs once after commit, in its own transaction; its publication is completed")
    void listenerAfterCommit() {
        place("m-1", "5");
        PaymentNotifier notifier = context.getBean(PaymentNotifier.class);

        await(() -> notifier.notified().contains("m-1"), "the module listener");
        await(() -> listenerPublications("NOT NULL").size() == 1, "the completed publication");
        assertThat("COMPLETED").isEqualTo(status(PaymentSaga.class));
        assertThat(notifier.notified()).containsExactly("m-1");
        assertThat(notifier.inTransaction()).containsExactly(true);
        assertThat(listenerPublications("NULL")).isEmpty();
    }

    @Test
    @DisplayName("a rolled-back unit of work notifies nobody and leaves no publication")
    void neverOnRollback() throws InterruptedException {
        place("m-2", "-1");

        await(() -> "FAILED".equals(status(PaymentSaga.class)), "the saga to fail");
        Thread.sleep(300);
        assertThat(context.getBean(PaymentNotifier.class).notified()).isEmpty();
        assertThat(jdbc().queryForList("SELECT listener_id FROM event_publication WHERE listener_id LIKE"
                + " '%PaymentNotifier%'", String.class)).isEmpty();
    }

    @Test
    @DisplayName("a failed listener's publication stays incomplete and is resubmitted")
    void resubmitted() {
        PaymentNotifier notifier = context.getBean(PaymentNotifier.class);
        notifier.failNext(1);
        place("m-3", "7");

        await(() -> notifier.inTransaction().size() == 1, "the failing listener call");
        await(() -> listenerPublications("NULL").size() == 1, "the incomplete publication");
        assertThat(notifier.notified()).isEmpty();

        context.getBean(IncompleteEventPublications.class).resubmitIncompletePublications(
                publication -> publication.getEvent() instanceof
                        io.github.gkosharovdev.sagaforge.spring.modulith.app.payments.PaymentEvents.PaymentTaken);

        await(() -> notifier.notified().contains("m-3"), "the resubmitted listener");
        await(() -> listenerPublications("NULL").isEmpty(), "the publication to complete");
    }
}
