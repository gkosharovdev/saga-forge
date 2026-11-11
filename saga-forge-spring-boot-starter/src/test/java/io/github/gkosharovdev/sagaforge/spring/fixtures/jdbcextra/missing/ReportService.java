package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbcextra.missing;

import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;

/** A handler with a {@link ZoneId} parameter no bean supplies: startup fails. */
public class ReportService {

    @Command
    public record ReportPayment(String orderId) {
    }

    @CommandHandler
    public void handle(ReportPayment command, ZoneId zone) {
        // not run
    }

    @Configuration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        ReportService reportService() {
            return new ReportService();
        }
    }
}
