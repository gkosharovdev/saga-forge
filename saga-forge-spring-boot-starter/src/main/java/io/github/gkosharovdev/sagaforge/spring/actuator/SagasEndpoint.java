package io.github.gkosharovdev.sagaforge.spring.actuator;

import io.github.gkosharovdev.sagaforge.core.operations.InstanceCounts;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.PageCursor;
import io.github.gkosharovdev.sagaforge.core.operations.RepairResult;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstancePage;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstanceQuery;
import io.github.gkosharovdev.sagaforge.core.operations.SagaOperations;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import java.security.Principal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

/**
 * The {@code sagas} Actuator endpoint over the runtime's {@link SagaOperations} ({@code
 * specs/015-saga-operations} contracts/starter-operations.md). Reads list and show saga instances
 * with their history; the write operation repairs one instance and is off unless the application
 * sets {@code management.endpoint.sagas.access=unrestricted}. A repair needs an authenticated
 * principal, whose name is recorded as the operator; secure the endpoint with Spring Security.
 *
 * <p>Instances are plain maps: the saga's business state is never shown, and its context values
 * only when {@code saga-forge.operations.expose-context-values} is set.
 */
@Endpoint(id = "sagas", defaultAccess = Access.READ_ONLY)
public class SagasEndpoint {

  private static final Map<String, BiFunction<SagaOperations, Repair, RepairResult>> ACTIONS =
      Map.of(
          "resume-compensation",
          (operations, repair) -> operations.resumeCompensation(repair.sagaId(), repair.request()),
          "compensate",
          (operations, repair) -> operations.compensate(repair.sagaId(), repair.request()),
          "resolve",
          (operations, repair) -> operations.markResolved(repair.sagaId(), repair.request()));

  private record Repair(String sagaId, OperatorRequest request) {}

  private final Supplier<SagaOperations> operations;
  private final boolean exposeContextValues;

  /**
   * @param operations the runtime's operations, read on every request (the runtime starts after the
   *     endpoint is created)
   * @param exposeContextValues whether a view shows the instance's context values
   */
  public SagasEndpoint(Supplier<SagaOperations> operations, boolean exposeContextValues) {
    this.operations = operations;
    this.exposeContextValues = exposeContextValues;
  }

  /** {@code GET /actuator/sagas}: one page of instances with the counts per status. */
  @ReadOperation
  public WebEndpointResponse<Map<String, Object>> list(
      String @Nullable [] status,
      String @Nullable [] type,
      @Nullable String modifiedBefore,
      @Nullable String modifiedAfter,
      @Nullable Integer limit,
      @Nullable String cursor) {
    SagaInstanceQuery query;
    try {
      query = query(status, type, modifiedBefore, modifiedAfter, limit, cursor);
    } catch (IllegalArgumentException | DateTimeParseException bad) {
      return new WebEndpointResponse<>(
          Map.of("error", String.valueOf(bad.getMessage())),
          WebEndpointResponse.STATUS_BAD_REQUEST);
    }
    SagaOperations ops = operations.get();
    SagaInstancePage page = ops.list(query);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("instances", page.instances().stream().map(SagasEndpoint::instance).toList());
    body.put("next", page.next().map(PageCursor::token).orElse(null));
    body.put("counts", counts(ops.count(Set.of())));
    return new WebEndpointResponse<>(body);
  }

  /** {@code GET /actuator/sagas/{sagaId}}: the instance and its history; 404 if unknown. */
  @ReadOperation
  public WebEndpointResponse<Map<String, Object>> view(@Selector String sagaId) {
    SagaOperations ops = operations.get();
    Optional<SagaInstance> found = ops.find(sagaId);
    if (found.isEmpty()) {
      return new WebEndpointResponse<>(WebEndpointResponse.STATUS_NOT_FOUND);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("instance", instance(found.get()));
    body.put("history", ops.history(sagaId).stream().map(SagasEndpoint::entry).toList());
    if (exposeContextValues) {
      body.put("context", new TreeMap<>(ops.context(sagaId)));
    }
    return new WebEndpointResponse<>(body);
  }

  /**
   * {@code POST /actuator/sagas/{sagaId}/{action}} with {@code {"reason": "..."}}: one repair, by
   * the authenticated principal. 401 without a principal, 400 for an unknown action or a blank
   * reason, 409 when the repair does not apply.
   */
  @WriteOperation
  public WebEndpointResponse<Map<String, Object>> repair(
      SecurityContext security,
      @Selector String sagaId,
      @Selector String action,
      @Nullable String reason) {
    Principal principal = security.getPrincipal();
    if (principal == null) {
      return new WebEndpointResponse<>(
          Map.of("error", "a repair needs an authenticated principal"), 401);
    }
    BiFunction<SagaOperations, Repair, RepairResult> repair = ACTIONS.get(action);
    if (repair == null) {
      return badRequest("unknown action '" + action + "'; use one of " + ACTIONS.keySet());
    }
    OperatorRequest request;
    try {
      request = new OperatorRequest(principal.getName(), reason);
    } catch (IllegalArgumentException bad) {
      return badRequest(bad.getMessage());
    }
    RepairResult result = repair.apply(operations.get(), new Repair(sagaId, request));
    Map<String, Object> body = new LinkedHashMap<>();
    return switch (result) {
      case RepairResult.Applied applied -> {
        body.put("result", "APPLIED");
        body.put("instance", instance(applied.instance()));
        yield new WebEndpointResponse<>(body);
      }
      case RepairResult.NotApplied notApplied -> {
        body.put("result", "NOT_APPLIED");
        body.put("reason", notApplied.reason().name());
        body.put(
            "status", notApplied.actualStatus() == null ? null : notApplied.actualStatus().name());
        yield new WebEndpointResponse<>(body, 409);
      }
    };
  }

  private static WebEndpointResponse<Map<String, Object>> badRequest(String message) {
    return new WebEndpointResponse<>(
        Map.of("error", String.valueOf(message)), WebEndpointResponse.STATUS_BAD_REQUEST);
  }

  private static SagaInstanceQuery query(
      String @Nullable [] status,
      String @Nullable [] type,
      @Nullable String modifiedBefore,
      @Nullable String modifiedAfter,
      @Nullable Integer limit,
      @Nullable String cursor) {
    SagaInstanceQuery query = SagaInstanceQuery.all();
    if (status != null && status.length > 0) {
      query =
          query.withStatuses(
              Arrays.stream(status)
                  .flatMap(value -> Arrays.stream(value.split(",")))
                  .map(String::trim)
                  .map(SagaStatus::valueOf)
                  .toArray(SagaStatus[]::new));
    }
    if (type != null && type.length > 0) {
      query = query.withSagaTypes(type);
    }
    if (modifiedBefore != null) {
      query = query.modifiedBefore(Instant.parse(modifiedBefore));
    }
    if (modifiedAfter != null) {
      query = query.modifiedAfter(Instant.parse(modifiedAfter));
    }
    if (limit != null) {
      query = query.limit(limit);
    }
    if (cursor != null) {
      query = query.after(PageCursor.fromToken(cursor));
    }
    return query;
  }

  private static Map<String, Object> counts(InstanceCounts counts) {
    Map<String, Object> view = new LinkedHashMap<>();
    Map<String, Long> byStatus = new TreeMap<>();
    counts.byStatus().forEach((status, count) -> byStatus.put(status.name(), count));
    view.put("byStatus", byStatus);
    view.put("compensationFailedUnresolved", counts.compensationFailedUnresolved());
    view.put("failedUnresolved", counts.failedUnresolved());
    return view;
  }

  /** {@code instance} without its business state. */
  static Map<String, Object> instance(SagaInstance instance) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("sagaId", instance.sagaId());
    view.put("sagaType", instance.sagaType());
    view.put("status", instance.status().name());
    view.put("associations", new TreeMap<>(instance.associations()));
    view.put("createdAt", instance.createdAt().toString());
    view.put("lastModifiedAt", instance.lastModifiedAt().toString());
    view.put("outcome", instance.outcome());
    view.put("compensationRound", instance.compensationRound());
    Map<String, Object> steps = new TreeMap<>();
    instance
        .steps()
        .forEach(
            (name, step) -> {
              Map<String, Object> stepView = new LinkedHashMap<>();
              stepView.put("status", step.status().name());
              stepView.put("attempts", step.attempts());
              stepView.put(
                  "compensation",
                  step.compensation() == null ? null : step.compensation().status().name());
              steps.put(name, stepView);
            });
    view.put("steps", steps);
    if (instance.failure() != null) {
      Map<String, Object> failure = new LinkedHashMap<>();
      failure.put("step", instance.failure().stepName());
      failure.put("causeType", instance.failure().causeType());
      failure.put("message", instance.failure().message());
      failure.put(
          "classification",
          instance.failure().classification() == null
              ? null
              : instance.failure().classification().name());
      view.put("failure", failure);
    }
    if (instance.compensationTrigger() != null) {
      Map<String, Object> trigger = new LinkedHashMap<>();
      trigger.put("kind", instance.compensationTrigger().kind().name());
      trigger.put("step", instance.compensationTrigger().stepName());
      if (instance.compensationTrigger().operatorRequest() != null) {
        trigger.put("operator", instance.compensationTrigger().operatorRequest().operator());
      }
      view.put("compensationTrigger", trigger);
    }
    if (instance.resolution() != null) {
      Map<String, Object> resolution = new LinkedHashMap<>();
      resolution.put("operator", instance.resolution().operator());
      resolution.put("reason", instance.resolution().reason());
      resolution.put("resolvedAt", instance.resolution().resolvedAt().toString());
      view.put("resolution", resolution);
    }
    return view;
  }

  private static Map<String, Object> entry(SagaLifecycleEntry entry) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("kind", entry.kind().name());
    view.put("at", entry.occurredAt().toString());
    view.put("step", entry.stepName());
    if (entry.operator() != null) {
      view.put("operator", entry.operator().operator());
      view.put("reason", entry.operator().reason());
    }
    return view;
  }

  /** The actions a repair accepts. */
  static List<String> actions() {
    return ACTIONS.keySet().stream().sorted().toList();
  }
}
