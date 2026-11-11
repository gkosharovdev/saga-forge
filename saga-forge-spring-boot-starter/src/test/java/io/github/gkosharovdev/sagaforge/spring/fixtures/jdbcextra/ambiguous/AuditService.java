package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbcextra.ambiguous;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc.PaymentProvider;

/** A handler whose unqualified {@link PaymentProvider} parameter matches two beans: startup fails. */
public class AuditService {

    @Command
    public record AuditPayment(String orderId) {
    }

    @CommandHandler
    public void handle(AuditPayment command, PaymentProvider provider) {
        // not run
    }

    @Configuration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        AuditService auditService() {
            return new AuditService();
        }
    }
}
