package io.github.gkosharovdev.sagaforge.spring.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.failure.FinalFailure;
import io.github.gkosharovdev.sagaforge.core.operations.InstanceCounts;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.Aborted;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.BDone;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.spring.SagaForgeProperties;
import io.github.gkosharovdev.sagaforge.transport.inmemory.InMemoryTransport;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.InMemoryMessageStore;
import java.security.Principal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code sagas} endpoint ({@code specs/015-saga-operations} contracts/starter-operations.md E1,
 * E4 to E7, and the list parameters; tasks.md T054), invoked through the endpoint bean over a
 * runtime with in-memory plug-ins.
 */
class SagasEndpointTest {

  /** A refusal the provider marks final. */
  static final class Refused extends RuntimeException implements FinalFailure {
    Refused() {
      super("refused");
    }
  }

  private static SecurityContext as(String name) {
    return new SecurityContext() {
      @Override
      public Principal getPrincipal() {
        return name == null ? null : () -> name;
      }

      @Override
      public boolean isUserInRole(String role) {
        return false;
      }
    };
  }

  private final UnwindFixture fixture = UnwindFixture.withEveryCompensation();
  private final SagasEndpoint endpoint = new SagasEndpoint(fixture::operations, false);

  /** A RUNNING saga with steps a and b, then one ended COMPENSATION_FAILED. */
  private String compensationFailed() {
    String sagaId = fixture.start("o-1");
    fixture.receive(new BDone("o-1"));
    fixture.journal().failOn("undoB", new Refused());
    fixture.receive(new Aborted("o-1"));
    return sagaId;
  }

  @Test
  @DisplayName("E1: list and view render instances without state, with history and counts")
  void listAndView() {
    String sagaId = compensationFailed();
    fixture.start("o-2");

    Map<String, Object> page = endpoint.list(null, null, null, null, null, null).getBody();
    Map<String, Object> view = endpoint.view(sagaId).getBody();

    assertThat(page.get("instances")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(2);
    assertThat(page.get("next")).isNull();
    assertThat(page.get("counts"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("compensationFailedUnresolved", 1L);
    assertThat(view.get("instance"))
        .asInstanceOf(InstanceOfAssertFactories.MAP)
        .containsEntry("status", "COMPENSATION_FAILED")
        .doesNotContainKey("state");
    assertThat(view.get("history")).asInstanceOf(InstanceOfAssertFactories.LIST).isNotEmpty();
    assertThat(endpoint.view("nope").getStatus()).isEqualTo(WebEndpointResponse.STATUS_NOT_FOUND);
  }

  @Test
  @DisplayName("E4/E5: the context key appears only when exposed")
  void contextValues() {
    String sagaId = fixture.start("o-1");

    assertThat(endpoint.view(sagaId).getBody()).doesNotContainKey("context");
    assertThat(new SagasEndpoint(fixture::operations, true).view(sagaId).getBody())
        .containsKey("context");
  }

  @Test
  @DisplayName(
      "list parameters: repeated status, a bad limit or status is 400, the cursor round-trips")
  void listParameters() {
    for (int i = 1; i <= 3; i++) {
      fixture.start("o-" + i);
    }

    WebEndpointResponse<Map<String, Object>> first =
        endpoint.list(new String[] {"RUNNING", "FAILED"}, null, null, null, 2, null);
    String next = (String) first.getBody().get("next");
    WebEndpointResponse<Map<String, Object>> second =
        endpoint.list(new String[] {"RUNNING"}, null, null, null, 2, next);

    assertThat(first.getBody().get("instances"))
        .asInstanceOf(InstanceOfAssertFactories.LIST)
        .hasSize(2);
    assertThat(next).isNotNull();
    assertThat(second.getBody().get("instances"))
        .asInstanceOf(InstanceOfAssertFactories.LIST)
        .hasSize(1);
    assertThat(endpoint.list(null, null, null, null, 0, null).getStatus())
        .isEqualTo(WebEndpointResponse.STATUS_BAD_REQUEST);
    assertThat(endpoint.list(new String[] {"ASLEEP"}, null, null, null, null, null).getStatus())
        .isEqualTo(WebEndpointResponse.STATUS_BAD_REQUEST);
    assertThat(endpoint.list(null, null, "yesterday", null, null, null).getStatus())
        .isEqualTo(WebEndpointResponse.STATUS_BAD_REQUEST);
    assertThat(endpoint.list(null, null, null, null, null, "not-a-cursor").getStatus())
        .isEqualTo(WebEndpointResponse.STATUS_BAD_REQUEST);
  }

  @Test
  @DisplayName(
      "repair: 401 without a principal, 400 for bad input, 409 when not applied, else applied")
  void repair() {
    String sagaId = compensationFailed();
    fixture.journal().clearFailures();

    assertThat(endpoint.repair(as(null), sagaId, "resume-compensation", "ok").getStatus())
        .isEqualTo(401);
    assertThat(endpoint.repair(as("alice"), sagaId, "explode", "ok").getStatus())
        .isEqualTo(WebEndpointResponse.STATUS_BAD_REQUEST);
    assertThat(endpoint.repair(as("alice"), sagaId, "resume-compensation", " ").getStatus())
        .isEqualTo(WebEndpointResponse.STATUS_BAD_REQUEST);
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATION_FAILED);

    WebEndpointResponse<Map<String, Object>> applied =
        endpoint.repair(as("alice"), sagaId, "resume-compensation", "refund done by hand");
    WebEndpointResponse<Map<String, Object>> again =
        endpoint.repair(as("alice"), sagaId, "resume-compensation", "again");

    assertThat(applied.getStatus()).isEqualTo(200);
    assertThat(applied.getBody()).containsEntry("result", "APPLIED");
    assertThat(fixture.committed(sagaId).status()).isEqualTo(SagaStatus.COMPENSATED);
    assertThat(again.getStatus()).isEqualTo(409);
    assertThat(again.getBody())
        .containsEntry("reason", "WRONG_STATUS")
        .containsEntry("status", "COMPENSATED");
    List<?> history = (List<?>) endpoint.view(sagaId).getBody().get("history");
    assertThat(history)
        .anySatisfy(
            entry ->
                assertThat(entry)
                    .asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("kind", "COMPENSATION_RESUMED")
                    .containsEntry("operator", "alice"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(SagaForgeProperties.class)
  static class Properties {}

  @Test
  @DisplayName("E6: registered with a SagaRuntime bean and no transport; absent without a runtime")
  void registration() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SagasEndpointConfiguration.class))
            .withUserConfiguration(Properties.class)
            .withPropertyValues("management.endpoints.web.exposure.include=sagas");

    runner
        .withBean(SagaRuntime.class, fixture::engine)
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(SagasEndpoint.class)
                    .doesNotHaveBean(InMemoryTransport.class));
    runner.run(context -> assertThat(context).doesNotHaveBean(SagasEndpoint.class));
    runner
        .withBean(SagaRuntime.class, fixture::engine)
        .withPropertyValues("management.endpoint.sagas.access=NONE")
        .run(context -> assertThat(context).doesNotHaveBean(SagasEndpoint.class));
  }

  @Test
  @DisplayName("E7: health details carry the unresolved counts and leave the status alone")
  void healthDetails() {
    compensationFailed();
    InMemoryTransport transport =
        InMemoryTransport.builder()
            .messageStore(new InMemoryMessageStore())
            .transactions(new InMemoryTransactionBoundary())
            .build();
    SagaForgeHealthIndicator health =
        new SagaForgeHealthIndicator(
            transport.queries(),
            transport::leadership,
            LeadershipSettings.defaults().leaseDuration(),
            Clock.systemUTC(),
            () -> Optional.of(fixture.operations().count(Set.of())));
    SagaForgeHealthIndicator without =
        new SagaForgeHealthIndicator(
            transport.queries(),
            transport::leadership,
            LeadershipSettings.defaults().leaseDuration(),
            Clock.systemUTC(),
            Optional::<InstanceCounts>empty);

    assertThat(health.health().getDetails())
        .containsEntry("instances.compensationFailed", 1L)
        .containsEntry("instances.failedUnresolved", 0L);
    assertThat(health.health().getStatus()).isEqualTo(without.health().getStatus());
    assertThat(health.health().getStatus()).isNotEqualTo(Status.DOWN);
  }
}
