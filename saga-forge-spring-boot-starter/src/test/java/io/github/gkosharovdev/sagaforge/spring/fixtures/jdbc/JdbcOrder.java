package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.event.DomainEvents;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.ConfirmOrder;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.JdbcOrderEvents.OrderConfirmed;

/**
 * An order mapped for Spring Data JDBC, with no Saga Forge loader or saver: loaded and saved through
 * {@link JdbcOrderRepository} ({@code specs/008-spring-boot-starter} US2 scenario 4).
 */
@Aggregate
@Table("jdbc_order")
public class JdbcOrder {

    @Id
    @AggregateIdentifier
    private final String orderId;
    private final boolean inStock;
    private String status;
    @Version
    private Long version;

    @PersistenceCreator
    JdbcOrder(String orderId, boolean inStock, String status, Long version) {
        this.orderId = orderId;
        this.inStock = inStock;
        this.status = status;
        this.version = version;
    }

    public static JdbcOrder placed(String orderId) {
        return new JdbcOrder(orderId, true, "PLACED", null);
    }

    @CommandHandler
    public void handle(ConfirmOrder command) {
        status = "CONFIRMED";
        DomainEvents.record(new OrderConfirmed(orderId));
    }

    public String orderId() {
        return orderId;
    }

    public String status() {
        return status;
    }

    public Long version() {
        return version;
    }
}
