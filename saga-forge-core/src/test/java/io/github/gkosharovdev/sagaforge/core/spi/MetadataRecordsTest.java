package io.github.gkosharovdev.sagaforge.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageKind;
import io.github.gkosharovdev.sagaforge.core.spi.store.NewMessage;
import io.github.gkosharovdev.sagaforge.core.spi.store.StoredMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The records {@code specs/019-process-metadata} gives a metadata component: old constructors give
 * empty metadata, and every copy keeps it.
 */
class MetadataRecordsTest {

  private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
  private static final SagaMetadata METADATA = SagaMetadata.of(Map.of("correlationId", "c-1"));

  @Test
  @DisplayName("an instance keeps its metadata through every with-method")
  void instance() {
    SagaInstance old =
        new SagaInstance(
            "saga-1",
            "com.example.Saga",
            Map.of(),
            Map.of(),
            SagaStatus.RUNNING,
            NOW,
            NOW,
            Map.of(),
            null,
            null);
    assertThat(old.metadata()).isEqualTo(SagaMetadata.empty());

    SagaInstance stamped = old.withMetadata(METADATA);

    assertThat(stamped.metadata()).isEqualTo(METADATA);
    assertThat(stamped.withPendingRetry(null).metadata()).isEqualTo(METADATA);
    assertThat(stamped.withOpenDeadlines(Map.of()).metadata()).isEqualTo(METADATA);
    assertThat(stamped.withCompensationRound(2).metadata()).isEqualTo(METADATA);
    assertThat(stamped.withResolution(null).metadata()).isEqualTo(METADATA);
    assertThat(stamped.withMetadata(null).metadata()).isEqualTo(SagaMetadata.empty());
  }

  @Test
  @DisplayName("a lifecycle entry defaults to empty metadata and takes it with withMetadata")
  void entry() {
    SagaLifecycleEntry entry =
        new SagaLifecycleEntry(
            "saga-1", SagaLifecycleEventKind.CREATED, null, List.of(), null, null, NOW);

    assertThat(entry.metadata()).isEqualTo(SagaMetadata.empty());
    assertThat(entry.withMetadata(METADATA).metadata()).isEqualTo(METADATA);
    assertThat(entry.withMetadata(METADATA).kind()).isEqualTo(SagaLifecycleEventKind.CREATED);
  }

  @Test
  @DisplayName("messages carry metadata from append to stored copies")
  void messages() {
    NewMessage entry =
        new NewMessage(
                "m-1",
                MessageKind.EVENT,
                "com.example.E",
                "payload",
                null,
                null,
                null,
                null,
                NOW,
                Map.of())
            .withMetadata(METADATA);

    StoredMessage stored = StoredMessage.appended(1, entry);

    assertThat(stored.metadata()).isEqualTo(METADATA);
    assertThat(stored.withoutPayload().metadata()).isEqualTo(METADATA);
    assertThat(stored.cancelled(NOW).metadata()).isEqualTo(METADATA);
    assertThat(entry.withMetadata(null).metadata()).isEqualTo(SagaMetadata.empty());
  }

  @Test
  @DisplayName("outgoing batches and outbox commands default to empty metadata")
  void batchesAndCommands() {
    OutgoingMessages batch = new OutgoingMessages("uow-1", null, null, List.of("event"));
    OutboxCommand command =
        new OutboxCommand(CommandId.unscoped("com.example.C"), "command", tx -> {});

    assertThat(batch.metadata()).isEqualTo(SagaMetadata.empty());
    assertThat(command.metadata()).isEqualTo(SagaMetadata.empty());
    assertThat(
            new OutgoingMessages(
                    "uow-1",
                    null,
                    null,
                    List.of("event"),
                    List.of(),
                    List.of(),
                    List.of(),
                    METADATA)
                .metadata())
        .isEqualTo(METADATA);
  }
}
