package io.github.gkosharovdev.sagaforge.example.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEventKind;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The {@code sagas} Actuator endpoint over real HTTP behind Spring Security ({@code
 * specs/015-saga-operations} contracts/starter-operations.md E2, E3; tasks.md T058): reads are
 * open, a repair needs the {@code OPERATOR} role and records the authenticated user as the
 * operator.
 */
@Tag("container")
class SagasEndpointWebContainerTest {

  /** Two users: {@code alice} operates sagas, {@code bob} only reads. */
  @Configuration(proxyBeanMethods = false)
  static class OperatorSecurity {

    @Bean
    SecurityFilterChain actuatorSecurity(HttpSecurity http) throws Exception {
      http.authorizeHttpRequests(
              requests ->
                  requests
                      .requestMatchers(HttpMethod.POST, "/actuator/sagas/**")
                      .hasRole("OPERATOR")
                      .anyRequest()
                      .permitAll())
          .httpBasic(Customizer.withDefaults())
          .csrf(csrf -> csrf.disable());
      return http.build();
    }

    @Bean
    InMemoryUserDetailsManager users() {
      return new InMemoryUserDetailsManager(
          User.withUsername("alice").password("{noop}alice-pw").roles("OPERATOR").build(),
          User.withUsername("bob").password("{noop}bob-pw").roles("VIEWER").build());
    }
  }

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static ConfigurableApplicationContext context;
  private static String base;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    context =
        new SpringApplication(OrderApplication.class, OperatorSecurity.class)
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--saga-forge.transport.poll-interval=50ms",
                "--management.endpoint.sagas.access=unrestricted");
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    base = "http://localhost:" + port + "/actuator/sagas";
  }

  @AfterAll
  static void stop() {
    if (context != null) {
      context.close();
    }
    POSTGRES.stop();
  }

  private static SagaInstance failedSaga(String orderId) throws InterruptedException {
    context.getBean(OrderPlacement.class).place(orderId, new BigDecimal("-1.00"), true);
    SagaRuntime runtime = context.getBean(SagaRuntime.class);
    await(
        () ->
            runtime.operations().findByAssociation("orderId", orderId).stream()
                .anyMatch(instance -> instance.status() == SagaStatus.FAILED),
        "the saga to fail");
    return runtime.operations().findByAssociation("orderId", orderId).get(0);
  }

  private static HttpResponse<String> resolve(String sagaId, String user, String password)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + "/" + sagaId + "/resolve"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"reason\":\"declined card, refunded by hand\"}"));
    if (user != null) {
      request.header(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + 15_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(25);
    }
  }

  @Test
  @DisplayName("E2: alice resolves a failed saga over HTTP and is recorded as the operator")
  void operatorResolves() throws Exception {
    SagaInstance failed = failedSaga("order-web-1");

    HttpResponse<String> response = resolve(failed.sagaId(), "alice", "alice-pw");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"result\":\"APPLIED\"");
    SagaRuntime runtime = context.getBean(SagaRuntime.class);
    assertThat(runtime.operations().find(failed.sagaId()).orElseThrow().resolution().operator())
        .isEqualTo("alice");
    SagaLifecycleEntry resolved =
        runtime.operations().history(failed.sagaId()).stream()
            .filter(entry -> entry.kind() == SagaLifecycleEventKind.RESOLVED)
            .findFirst()
            .orElseThrow();
    assertThat(resolved.operator().operator()).isEqualTo("alice");

    HttpResponse<String> view =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(base + "/" + failed.sagaId())).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(view.statusCode()).isEqualTo(200);
    assertThat(view.body()).contains("\"RESOLVED\"").doesNotContain("\"context\"");
  }

  @Test
  @DisplayName("E3: anonymous and non-operator repairs are refused and change nothing")
  void othersAreRefused() throws Exception {
    SagaInstance failed = failedSaga("order-web-2");

    assertThat(resolve(failed.sagaId(), null, null).statusCode()).isEqualTo(401);
    assertThat(resolve(failed.sagaId(), "bob", "bob-pw").statusCode()).isEqualTo(403);
    assertThat(
            context
                .getBean(SagaRuntime.class)
                .operations()
                .find(failed.sagaId())
                .orElseThrow()
                .resolution())
        .isNull();
  }
}
