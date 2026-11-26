package io.github.gkosharovdev.sagaforge.storage.postgres;

import static io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.deadline.DeadlineReached;
import io.github.gkosharovdev.sagaforge.transport.inmemory.LeadershipSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.RelayRole;
import io.github.gkosharovdev.sagaforge.transport.inmemory.TransportSettings;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.CardService;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.HoldConfirmed;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.HoldPlaced;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.HoldSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.RenewalSaga;
import io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures.SubscriptionStarted;
import io.github.gkosharovdev.sagaforge.transport.inmemory.support.TransportHarness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Deadlines and scheduled commands across a handover between two instances on one database ({@code
 * specs/013-active-standby-relay} US3, SC-004, FR-011): due messages follow the leadership, so each
 * runs once, on whichever instance leads when it comes due.
 */
@Tag("container")
class MultiInstanceDeadlineContainerTest {

  private static final Duration WAIT = Duration.ofSeconds(30);
  private static final Map<String, String> LAPSE = Map.of("hold.lapse", "PT1.5S");
  private static final TransportSettings SETTINGS =
      TransportHarness.FAST.withLeadership(
          new LeadershipSettings(
              Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofMillis(200)));

  private final List<Replica> replicas = new ArrayList<>();

  @BeforeEach
  void resetDatabase() {
    PostgresTestSupport.reset();
  }

  @AfterEach
  void stopAll() {
    replicas.forEach(Replica::stop);
  }

  private Replica replica(Class<?>... model) {
    Replica replica = new Replica(SETTINGS, LAPSE, model);
    replicas.add(replica);
    return replica;
  }

  @Test
  @DisplayName(
      "SC-004: deadlines due before, during and after a handover each run their timeout step once")
  void deadlinesAcrossAHandover() {
    Replica a = replica(HoldSaga.class);
    Replica b = replica(HoldSaga.class);
    a.publish(new HoldPlaced("h-before"));
    await(() -> status("h-before").equals("COMPENSATED"), WAIT, "the first hold to lapse");

    b.publish(new HoldPlaced("h-during"));
    await(() -> hasOpenDeadline("h-during"), WAIT, "the second hold's deadline");
    a.stop();
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");
    b.publish(new HoldPlaced("h-after"));

    await(() -> status("h-during").equals("COMPENSATED"), WAIT, "the second hold to lapse");
    await(() -> status("h-after").equals("COMPENSATED"), WAIT, "the third hold to lapse");
    List<DeadlineReached> delivered =
        Stream.concat(
                a.delivered(DeadlineReached.class).stream(),
                b.delivered(DeadlineReached.class).stream())
            .toList();
    assertThat(delivered).hasSize(3);
    assertThat(delivered.stream().map(DeadlineReached::deadlineId).distinct()).hasSize(3);
    assertThat(a.delivered(DeadlineReached.class)).hasSize(1);
    assertThat(
            Stream.concat(
                a.runtimeLog.byEvent("saga.deadline.ignored").stream(),
                b.runtimeLog.byEvent("saga.deadline.ignored").stream()))
        .isEmpty();
  }

  @Test
  @DisplayName("SC-004: a deadline closed by an event appended on the standby never runs")
  void cancelledOnTheStandby() {
    Replica a = replica(HoldSaga.class);
    Replica b = replica(HoldSaga.class);
    a.publish(new HoldPlaced("h-1"));
    await(() -> hasOpenDeadline("h-1"), WAIT, "the deadline");

    b.publish(new HoldConfirmed("h-1"));
    await(() -> status("h-1").equals("COMPLETED"), WAIT, "the hold to be confirmed");
    sleepPast(LAPSE.get("hold.lapse"));

    assertThat(a.delivered(DeadlineReached.class)).isEmpty();
    assertThat(b.delivered(DeadlineReached.class)).isEmpty();
    assertThat(
            PostgresTestSupport.queryRows(
                "SELECT status FROM saga_message_store WHERE payload_type = ?",
                DeadlineReached.class.getName()))
        .singleElement()
        .satisfies(row -> assertThat(row.get("status")).isEqualTo("CANCELLED"));
  }

  @Test
  @DisplayName("US3: a command scheduled on one instance and due during a handover is handled once")
  void scheduledCommandAcrossAHandover() {
    Replica a = replica(RenewalSaga.class, CardService.class);
    Replica b = replica(RenewalSaga.class, CardService.class);
    b.receive(new SubscriptionStarted("sub-1", "PT1.5S"));

    a.stop();
    await(() -> b.role() == RelayRole.ACTIVE, WAIT, "B to take over");
    await(() -> !b.cards.charges().isEmpty(), WAIT, "the renewal charge");
    sleepPast("PT1S");

    assertThat(a.cards.charges()).isEmpty();
    assertThat(b.cards.charges())
        .singleElement()
        .satisfies(c -> assertThat(c.invoiceId()).isEqualTo("sub-1"));
  }

  private static String status(String holdId) {
    List<Map<String, Object>> rows =
        PostgresTestSupport.queryRows(
            "SELECT i.status FROM saga_instance i JOIN saga_association s ON s.saga_id = i.saga_id"
                + " WHERE s.association_property = 'holdId' AND s.association_value = ?",
            holdId);
    return rows.isEmpty() ? "NONE" : (String) rows.get(0).get("status");
  }

  private static boolean hasOpenDeadline(String holdId) {
    return !PostgresTestSupport.queryRows(
            "SELECT 1 FROM saga_instance i JOIN saga_association s ON s.saga_id = i.saga_id"
                + " WHERE s.association_value = ? AND i.open_deadlines <> '{}'::jsonb",
            holdId)
        .isEmpty();
  }

  private static void sleepPast(String duration) {
    try {
      Thread.sleep(Duration.parse(duration).toMillis() + 500);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", interrupted);
    }
  }
}
