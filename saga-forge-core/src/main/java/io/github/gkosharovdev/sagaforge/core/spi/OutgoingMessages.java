package io.github.gkosharovdev.sagaforge.core.spi;

import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import java.util.List;

/**
 * The outgoing messages of one committed unit of work, as given to {@link
 * Outbox#store(OutgoingMessages)}: the buffered events and, since {@code
 * specs/007-in-memory-transport}, the {@code @Command(delivery = OUTBOX)} commands sent in it.
 * Named {@code HandedOffEvents} before 007.
 *
 * <p>The canonical constructor throws {@link IllegalArgumentException} if {@code unitOfWorkId},
 * {@code events}, {@code commands} or an element of them is {@code null}, if {@code events}, {@code
 * commands}, {@code recordedEvents} and {@code scheduled} are all empty, or if exactly one of
 * {@code sagaType} and {@code sagaId} is present.
 *
 * @param unitOfWorkId the unit of work's identifier, unique per unit of work, saga-scoped or not;
 *     non-null
 * @param sagaType fully qualified {@code @Saga} class name of the owning instance; non-null if and
 *     only if the unit of work is saga-scoped
 * @param sagaId the owning instance; non-null if and only if the unit of work is saga-scoped
 * @param events the events, in recording order; non-null, no {@code null} element; an unmodifiable
 *     defensive copy. Its last element may be an outcome event ({@code core.outcome.SagaOutcome},
 *     since {@code specs/011-saga-outcome-events}) when the unit of work ended the instance
 * @param commands the outbox commands, in send order; non-null, no {@code null} element; an
 *     unmodifiable defensive copy
 * @param recordedEvents every event recorded in the unit of work, in recording order, including
 *     events a step of the owning instance ran in process; a superset of {@code events} (since
 *     {@code specs/008-spring-boot-starter}); non-null, no {@code null} element; an unmodifiable
 *     defensive copy. Ends with the same outcome event as {@code events}, if there is one
 * @param scheduled messages to store with a due time: deadlines the unit of work started and
 *     commands it scheduled ({@code specs/017-saga-deadlines}); non-null, no {@code null} element;
 *     an unmodifiable defensive copy. Non-empty only for an outbox that {@linkplain
 *     Outbox#supportsScheduling() supports scheduling}
 * @param metadata the process metadata every message of the batch carries: the owning instance's,
 *     or the work's for a unit of work outside any instance ({@code specs/019-process-metadata});
 *     {@code null} becomes empty
 */
public record OutgoingMessages(
    String unitOfWorkId,
    String sagaType,
    String sagaId,
    List<Object> events,
    List<OutgoingCommand> commands,
    List<Object> recordedEvents,
    List<ScheduledMessage> scheduled,
    SagaMetadata metadata) {

  /** Validates the components and keeps unmodifiable copies; a {@code null} metadata is empty. */
  public OutgoingMessages {
    if (unitOfWorkId == null) {
      throw new IllegalArgumentException("unitOfWorkId must not be null");
    }
    if ((sagaType == null) != (sagaId == null)) {
      throw new IllegalArgumentException(
          "sagaType and sagaId must be both present or both absent, but sagaType is "
              + sagaType
              + " and sagaId is "
              + sagaId);
    }
    events = copyWithoutNulls(events, "events");
    commands = copyWithoutNulls(commands, "commands");
    recordedEvents = copyWithoutNulls(recordedEvents, "recordedEvents");
    scheduled = copyWithoutNulls(scheduled, "scheduled");
    if (events.isEmpty() && commands.isEmpty() && recordedEvents.isEmpty() && scheduled.isEmpty()) {
      throw new IllegalArgumentException(
          "events must not be empty when there are no commands, recorded events or scheduled messages");
    }
    metadata = metadata == null ? SagaMetadata.empty() : metadata;
  }

  /**
   * A batch without metadata, as before {@code specs/019-process-metadata}.
   *
   * @throws IllegalArgumentException under the rules of the canonical constructor
   */
  public OutgoingMessages(
      String unitOfWorkId,
      String sagaType,
      String sagaId,
      List<Object> events,
      List<OutgoingCommand> commands,
      List<Object> recordedEvents,
      List<ScheduledMessage> scheduled) {
    this(
        unitOfWorkId,
        sagaType,
        sagaId,
        events,
        commands,
        recordedEvents,
        scheduled,
        SagaMetadata.empty());
  }

  /**
   * A batch without scheduled messages, as before {@code specs/017-saga-deadlines}.
   *
   * @throws IllegalArgumentException under the rules of the canonical constructor
   */
  public OutgoingMessages(
      String unitOfWorkId,
      String sagaType,
      String sagaId,
      List<Object> events,
      List<OutgoingCommand> commands,
      List<Object> recordedEvents) {
    this(unitOfWorkId, sagaType, sagaId, events, commands, recordedEvents, List.of());
  }

  /**
   * A batch whose recorded events are its {@code events}, as before {@code
   * specs/008-spring-boot-starter}.
   *
   * @throws IllegalArgumentException under the rules of the canonical constructor
   */
  public OutgoingMessages(
      String unitOfWorkId,
      String sagaType,
      String sagaId,
      List<Object> events,
      List<OutgoingCommand> commands) {
    this(unitOfWorkId, sagaType, sagaId, events, commands, events);
  }

  /**
   * A batch of events only, as before {@code specs/007-in-memory-transport}.
   *
   * @throws IllegalArgumentException under the rules of the canonical constructor; {@code events}
   *     must then be non-empty
   */
  public OutgoingMessages(
      String unitOfWorkId, String sagaType, String sagaId, List<Object> events) {
    this(unitOfWorkId, sagaType, sagaId, events, List.of());
  }

  private static <T> List<T> copyWithoutNulls(List<T> elements, String name) {
    if (elements == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    for (T element : elements) {
      if (element == null) {
        throw new IllegalArgumentException(name + " must not contain null elements");
      }
    }
    return List.copyOf(elements);
  }
}
