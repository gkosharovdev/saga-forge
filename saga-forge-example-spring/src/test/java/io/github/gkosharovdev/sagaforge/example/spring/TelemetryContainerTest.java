package io.github.gkosharovdev.sagaforge.example.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * The Spring example traced through Micrometer Tracing's OpenTelemetry bridge, with no Saga Forge code
 * ({@code specs/010-otel-observability} US4, SC-005, SC-008): the saga's spans are in the trace of the span that placed
 * the order.
 */
@Tag("container")
class TelemetryContainerTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    /** A fresh exporter per application context: closing a context shuts its exporter down. */
    private static volatile InMemorySpanExporter spans = InMemorySpanExporter.create();

    /** Exports to memory; Spring Boot's tracing auto-configuration picks the exporter up. */
    @Configuration(proxyBeanMethods = false)
    static class InMemoryExport {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return spans;
        }
    }

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    private static ConfigurableApplicationContext start(String... extra) {
        spans = InMemorySpanExporter.create();
        String[] base = { "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--saga-forge.storage.postgres.initialize-schema=always",
                "--saga-forge.transport.poll-interval=50ms", "--management.tracing.sampling.probability=1.0" };
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return SpringApplication.run(new Class<?>[] { OrderApplication.class, InMemoryExport.class }, args);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what + "; spans: "
                        + spans.getFinishedSpanItems().stream().map(SpanData::getName).toList());
            }
            Thread.sleep(25);
        }
    }

    private static List<SpanData> sagaSpans() {
        return spans.getFinishedSpanItems().stream().filter(span -> span.getName().startsWith("saga_forge.")).toList();
    }

    @Test
    @DisplayName("saga spans join the trace of the span that placed the order")
    void sagaSpansInTheRequestTrace() throws InterruptedException {
        try (ConfigurableApplicationContext context = start()) {
            Tracer tracer = context.getBean(Tracer.class);
            Span request = tracer.nextSpan().name("place-order").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
                context.getBean(OrderPlacement.class).place("order-t1", new BigDecimal("12.00"), true);
            } finally {
                request.end();
            }

            await(() -> sagaSpans().stream().anyMatch(span -> span.getName().equals("saga_forge.handle")
                    && "COMPLETED".equals(span.getAttributes().get(
                            io.opentelemetry.api.common.AttributeKey.stringKey("saga_forge.handling.outcome")))),
                    "the completed saga's handle span");

            assertThat(sagaSpans()).isNotEmpty().allSatisfy(span ->
                    assertThat(span.getTraceId()).isEqualTo(request.context().traceId()));
            assertThat(sagaSpans()).extracting(SpanData::getName)
                    .contains("saga_forge.delivery", "saga_forge.handle", "saga_forge.attempt", "saga_forge.step",
                            "saga_forge.command");
        }
    }

    @Test
    @DisplayName("saga-forge.telemetry.tracing.enabled=false records no saga span")
    void tracingOff() throws InterruptedException {
        try (ConfigurableApplicationContext context = start("--saga-forge.telemetry.tracing.enabled=false")) {
            Tracer tracer = context.getBean(Tracer.class);
            Span request = tracer.nextSpan().name("place-order").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
                context.getBean(OrderPlacement.class).place("order-t2", new BigDecimal("12.00"), true);
            } finally {
                request.end();
            }
            await(() -> spans.getFinishedSpanItems().stream().anyMatch(span -> span.getName().equals("place-order")),
                    "the request span");
            Thread.sleep(1_000);

            assertThat(sagaSpans()).isEmpty();
        }
    }
}
