package io.github.gkosharovdev.sagaforge.core.spi.model;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The built-in vocabulary: Saga Forge's {@code @Aggregate}, {@code @AggregateIdentifier},
 * {@code @DomainEvent}, {@code @Command} and {@code @CommandHandler}. The only class that reads
 * these annotations reflectively (guarded by {@code CoreDomainAnnotationAccessTest}).
 *
 * <p>Displays are the simple annotation names, so every error text of a model marked with Saga
 * Forge's annotations reads exactly as before vocabularies existed.
 */
final class SagaForgeAnnotations implements DomainTypeResolver {

  static final String NAME = "Saga Forge";

  private static final Marker AGGREGATE = marker("@Aggregate");
  private static final Marker AGGREGATE_IDENTIFIER = marker("@AggregateIdentifier");
  private static final Marker DOMAIN_EVENT = marker("@DomainEvent");
  private static final Marker COMMAND = marker("@Command");
  private static final Marker COMMAND_HANDLER = marker("@CommandHandler");

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<RoleMarker> role(Class<?> type) {
    if (type.isAnnotationPresent(Aggregate.class)) {
      return Optional.of(new RoleMarker(DomainRole.AGGREGATE, AGGREGATE));
    }
    if (type.isAnnotationPresent(DomainEvent.class)) {
      return Optional.of(new RoleMarker(DomainRole.DOMAIN_EVENT, DOMAIN_EVENT));
    }
    if (type.isAnnotationPresent(Command.class)) {
      return Optional.of(new RoleMarker(DomainRole.COMMAND, COMMAND));
    }
    return Optional.empty();
  }

  /**
   * The declared {@code @AggregateIdentifier} fields, in declaration order (a record component
   * counts through its field); on a {@code @DomainEvent} each names the event's {@code aggregate}.
   */
  @Override
  public List<IdentifierMember> identifiers(Class<?> type) {
    DomainEvent event = type.getAnnotation(DomainEvent.class);
    List<IdentifierMember> identifiers = new ArrayList<>();
    for (Field field : type.getDeclaredFields()) {
      if (field.isAnnotationPresent(AggregateIdentifier.class)
          && !Modifier.isStatic(field.getModifiers())) {
        IdentifierMember member = IdentifierMember.ofField(field, AGGREGATE_IDENTIFIER);
        identifiers.add(event == null ? member : member.naming(event.aggregate()));
      }
    }
    return List.copyOf(identifiers);
  }

  @Override
  public Optional<Marker> commandHandler(Executable member) {
    return member.isAnnotationPresent(CommandHandler.class)
        ? Optional.of(COMMAND_HANDLER)
        : Optional.empty();
  }

  @Override
  public String describe(DomainRole role) {
    return switch (role) {
      case AGGREGATE -> AGGREGATE.display();
      case DOMAIN_EVENT -> DOMAIN_EVENT.display();
      case COMMAND -> COMMAND.display();
    };
  }

  @Override
  public String describeIdentifier(DomainRole role) {
    return AGGREGATE_IDENTIFIER.display() + " field";
  }

  /** The delivery a {@code @Command} type declares; {@code IN_UNIT_OF_WORK} for any other type. */
  static CommandDelivery delivery(Class<?> commandType) {
    Command command = commandType.getAnnotation(Command.class);
    return command == null ? CommandDelivery.IN_UNIT_OF_WORK : command.delivery();
  }

  private static Marker marker(String display) {
    return new Marker(NAME, display);
  }
}
