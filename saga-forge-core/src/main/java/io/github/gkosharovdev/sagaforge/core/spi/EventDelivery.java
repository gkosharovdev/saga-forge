package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;

/**
 * One event handed to an {@link EventReceiver} by a transport, with what the transport knows about
 * the message that carried it ({@code specs/019-process-metadata} contracts/propagation.md).
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code event} is {@code
 * null} or {@code sourceSagaId} is blank.
 *
 * @param event the domain event or outcome event; non-null
 * @param sourceSagaId the saga instance whose unit of work recorded the event ({@code
 *     specs/014-publish-once}); {@code null} if none, else non-blank
 * @param metadata the message's metadata; {@code null} becomes empty
 */
public record EventDelivery(Object event, String sourceSagaId, SagaMetadata metadata) {

  /** Validates the components; a {@code null} {@code metadata} becomes empty. */
  public EventDelivery {
    if (event == null) {
      throw new IllegalArgumentException("event must not be null");
    }
    if (sourceSagaId != null && sourceSagaId.isBlank()) {
      throw new IllegalArgumentException("sourceSagaId must be null or non-blank");
    }
    metadata = metadata == null ? SagaMetadata.empty() : metadata;
  }
}
