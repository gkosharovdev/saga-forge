package io.github.gkosharovdev.sagaforge.spring.actuator;

import io.github.gkosharovdev.sagaforge.core.spi.AggregateEventSource;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import io.github.gkosharovdev.sagaforge.spring.metadata.SagaForgeMetadataConfiguration;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.MessageStoreQueries;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * Read-only operator view of the message store ({@code specs/008-spring-boot-starter} FR-020,
 * contracts/actuator.md): message counts per status, capped at 1,000 each, the age of the oldest
 * unconsumed message, and this instance's relay role with the store's leader ({@code
 * specs/013-active-standby-relay} FR-022), the enabled vocabularies of domain markers and the
 * registered aggregate event sources ({@code specs/016-marker-spi-jmolecules} FR-030), and, with
 * Postgres storage, the schema's version and check ({@code specs/018-versioned-schema-migrations}
 * FR-020). No write operation (G-008-02).
 */
@Endpoint(id = "sagaforge")
public class SagaForgeEndpoint {

  private final MessageStoreQueries queries;
  private final Supplier<LeadershipStatus> leadership;
  private final DomainTypes domainTypes;
  private final List<String> eventSources;
  private final Supplier<Map<String, Object>> schema;
  private final Map<String, Object> metadata;

  /**
   * An endpoint reporting Saga Forge's annotations as the only vocabulary.
   *
   * @param leadership this instance's view of the leadership, read on every request
   */
  public SagaForgeEndpoint(MessageStoreQueries queries, Supplier<LeadershipStatus> leadership) {
    this(queries, leadership, DomainTypes.builtIn(), List.of());
  }

  /**
   * @param leadership this instance's view of the leadership, read on every request
   * @param domainTypes the vocabularies the runtime recognises, listed by name
   * @param eventSources the aggregate event sources the runtime asks, in order, listed by class
   */
  public SagaForgeEndpoint(
      MessageStoreQueries queries,
      Supplier<LeadershipStatus> leadership,
      DomainTypes domainTypes,
      List<? extends AggregateEventSource> eventSources) {
    this(queries, leadership, domainTypes, eventSources, () -> null);
  }

  /**
   * @param leadership this instance's view of the leadership, read on every request
   * @param domainTypes the vocabularies the runtime recognises, listed by name
   * @param eventSources the aggregate event sources the runtime asks, in order, listed by class
   * @param schema the {@code schema} object, or {@code null} when there is no schema initializer
   */
  public SagaForgeEndpoint(
      MessageStoreQueries queries,
      Supplier<LeadershipStatus> leadership,
      DomainTypes domainTypes,
      List<? extends AggregateEventSource> eventSources,
      Supplier<Map<String, Object>> schema) {
    this(
        queries,
        leadership,
        domainTypes,
        eventSources,
        schema,
        SagaForgeMetadataConfiguration.describe(null));
  }

  /**
   * @param leadership this instance's view of the leadership, read on every request
   * @param domainTypes the vocabularies the runtime recognises, listed by name
   * @param eventSources the aggregate event sources the runtime asks, in order, listed by class
   * @param schema the {@code schema} object, or {@code null} when there is no schema initializer
   * @param metadata the {@code metadata} object ({@code specs/019-process-metadata}): the accessor
   *     the runtime uses, and the MDC keys when it is the MDC accessor
   */
  public SagaForgeEndpoint(
      MessageStoreQueries queries,
      Supplier<LeadershipStatus> leadership,
      DomainTypes domainTypes,
      List<? extends AggregateEventSource> eventSources,
      Supplier<Map<String, Object>> schema,
      Map<String, Object> metadata) {
    this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    this.schema = schema;
    this.queries = queries;
    this.leadership = leadership;
    this.domainTypes = domainTypes;
    this.eventSources = eventSources.stream().map(source -> source.getClass().getName()).toList();
  }

  @ReadOperation
  public Map<String, Object> status() {
    MessageStoreQueries.Status status = queries.status();
    Map<String, Integer> counts = new TreeMap<>();
    status.counts().forEach((messageStatus, count) -> counts.put(messageStatus.name(), count));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("counts", counts);
    status.oldestUnconsumed().ifPresent(age -> payload.put("oldestUnconsumed", age.toString()));
    payload.put("leadership", leadership(leadership.get()));
    payload.put("vocabularies", domainTypes.vocabularies());
    payload.put("eventSources", eventSources);
    payload.put("metadata", metadata);
    Map<String, Object> schemaView = schema.get();
    if (schemaView != null) {
      payload.put("schema", schemaView);
    }
    return payload;
  }

  private static Map<String, Object> leadership(LeadershipStatus status) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("role", status.role().name());
    view.put("relayId", status.relayId());
    status.currentLeader().ifPresent(leader -> view.put("leader", leader(leader)));
    return view;
  }

  private static Map<String, Object> leader(LeaderLease lease) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("holderId", lease.holderId());
    view.put("holderHost", lease.holderHost());
    view.put("fencingToken", lease.fencingToken());
    view.put("acquiredAt", lease.acquiredAt().toString());
    view.put("expiresAt", lease.expiresAt().toString());
    return view;
  }
}
