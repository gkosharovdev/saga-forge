package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbcextra.idtype;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.repository.CrudRepository;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/**
 * An aggregate whose {@code @AggregateIdentifier} is a {@link LocalDate} while its repository's id is a {@link UUID}:
 * not convertible, so startup fails naming both types.
 */
@Aggregate
@Table("jdbc_invoice")
public class Invoice {

    @Id
    private UUID invoiceId;

    @AggregateIdentifier
    private transient LocalDate issuedOn;

    @Command
    public record SendInvoice(@AggregateIdentifier LocalDate issuedOn) {
    }

    @CommandHandler
    public void handle(SendInvoice command) {
        // not run
    }

    public interface InvoiceRepository extends CrudRepository<Invoice, UUID> {
    }

    @EnableJdbcRepositories(basePackageClasses = Invoice.class, considerNestedRepositories = true)
    public static class Config {
    }
}
