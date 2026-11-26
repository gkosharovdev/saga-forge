package io.github.gkosharovdev.sagaforge.spring.actuator;

import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipStatus;
import io.github.gkosharovdev.sagaforge.transport.inmemory.MessageStoreQueries;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * Read-only operator view of the message store ({@code specs/008-spring-boot-starter} FR-020,
 * contracts/actuator.md): message counts per status, capped at 1,000 each, the age of the oldest
 * unconsumed message, and this instance's relay role with the store's leader ({@code
 * specs/013-active-standby-relay} FR-022). No write operation (G-008-02).
 */
@Endpoint(id = "sagaforge")
public class SagaForgeEndpoint {

  private final MessageStoreQueries queries;
  private final Supplier<LeadershipStatus> leadership;

  /**
   * @param leadership this instance's view of the leadership, read on every request
   */
  public SagaForgeEndpoint(MessageStoreQueries queries, Supplier<LeadershipStatus> leadership) {
    this.queries = queries;
    this.leadership = leadership;
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
