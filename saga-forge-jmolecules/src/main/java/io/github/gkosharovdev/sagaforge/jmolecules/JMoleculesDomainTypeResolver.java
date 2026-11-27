package io.github.gkosharovdev.sagaforge.jmolecules;

import io.github.gkosharovdev.sagaforge.core.spi.model.DomainRole;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypeResolver;
import io.github.gkosharovdev.sagaforge.core.spi.model.IdentifierMember;
import io.github.gkosharovdev.sagaforge.core.spi.model.Marker;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jmolecules.ddd.annotation.Identity;
import org.jmolecules.ddd.types.AggregateRoot;

/**
 * Recognises jMolecules' DDD, event and CQRS markers as Saga Forge domain roles
 * (contracts/jmolecules-module.md):
 *
 * <ul>
 *   <li>aggregate: {@code @AggregateRoot} or {@code AggregateRoot<T, ID>}, identified by its
 *       {@code @Identity} field or method, else, for {@code AggregateRoot<T, ID>}, by {@code
 *       getId()}
 *   <li>domain event: {@code @DomainEvent} or {@code DomainEvent} ({@code jmolecules-events})
 *   <li>command: {@code @Command} ({@code jmolecules-cqrs-architecture})
 *   <li>command handler: {@code @CommandHandler} on a method or constructor
 * </ul>
 *
 * <p>An event or command is linked to its aggregate by an {@code Association<A, ID>} member, an
 * {@code @Association} member or an {@code Identifier}-typed member. Without the optional events or
 * CQRS artifact on the class path, the resolver recognises no events or commands respectively.
 * Saga-side annotations, {@code @DomainEventHandler}, {@code @Repository} and {@code @Service} are
 * not recognised; an outbox command keeps Saga Forge's {@code @Command(delivery = OUTBOX)} beside
 * jMolecules' {@code @Command}.
 *
 * <p>Stateless; thread-safe.
 */
public final class JMoleculesDomainTypeResolver implements DomainTypeResolver {

  /** The vocabulary's name. */
  public static final String NAME = "jMolecules";

  private static final Marker AGGREGATE_ROOT_ANNOTATION = marker("@AggregateRoot");
  private static final Marker AGGREGATE_ROOT_TYPE = marker("AggregateRoot<T, ID>");
  private static final Marker DOMAIN_EVENT_ANNOTATION = marker("@DomainEvent");
  private static final Marker DOMAIN_EVENT_TYPE = marker("DomainEvent");
  private static final Marker COMMAND = marker("@Command");
  private static final Marker COMMAND_HANDLER = marker("@CommandHandler");
  private static final Marker IDENTITY = marker("@Identity");

  private final OptionalMarker eventAnnotation;
  private final OptionalMarker eventType;
  private final OptionalMarker command;
  private final OptionalMarker deprecatedCommand;
  private final OptionalMarker commandHandler;
  private final OptionalMarker deprecatedCommandHandler;

  /** A resolver probing the optional jMolecules artifacts with this class's class loader. */
  public JMoleculesDomainTypeResolver() {
    this(JMoleculesDomainTypeResolver.class.getClassLoader());
  }

  /** A resolver probing the optional jMolecules artifacts with {@code loader}. */
  JMoleculesDomainTypeResolver(ClassLoader loader) {
    this.eventAnnotation =
        OptionalMarker.named("org.jmolecules.event.annotation.DomainEvent", loader);
    this.eventType = OptionalMarker.named("org.jmolecules.event.types.DomainEvent", loader);
    this.command = OptionalMarker.named("org.jmolecules.architecture.cqrs.Command", loader);
    this.deprecatedCommand =
        OptionalMarker.named("org.jmolecules.architecture.cqrs.annotation.Command", loader);
    this.commandHandler =
        OptionalMarker.named("org.jmolecules.architecture.cqrs.CommandHandler", loader);
    this.deprecatedCommandHandler =
        OptionalMarker.named("org.jmolecules.architecture.cqrs.annotation.CommandHandler", loader);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Optional<RoleMarker> role(Class<?> type) {
    return roles(type).stream().findFirst();
  }

  /**
   * jMolecules' markers can overlap, for example an {@code @AggregateRoot} that is a {@code
   * DomainEvent}.
   */
  @Override
  public List<RoleMarker> roles(Class<?> type) {
    List<RoleMarker> roles = new ArrayList<>();
    aggregateMarker(type)
        .ifPresent(marker -> roles.add(new RoleMarker(DomainRole.AGGREGATE, marker)));
    eventMarker(type)
        .ifPresent(marker -> roles.add(new RoleMarker(DomainRole.DOMAIN_EVENT, marker)));
    if (command.annotates(type) || deprecatedCommand.annotates(type)) {
      roles.add(new RoleMarker(DomainRole.COMMAND, COMMAND));
    }
    return List.copyOf(roles);
  }

  private static Optional<Marker> aggregateMarker(Class<?> type) {
    if (type.isAnnotationPresent(org.jmolecules.ddd.annotation.AggregateRoot.class)) {
      return Optional.of(AGGREGATE_ROOT_ANNOTATION);
    }
    return isAggregateRootType(type) ? Optional.of(AGGREGATE_ROOT_TYPE) : Optional.empty();
  }

  private Optional<Marker> eventMarker(Class<?> type) {
    if (eventAnnotation.annotates(type)) {
      return Optional.of(DOMAIN_EVENT_ANNOTATION);
    }
    return eventType.isSupertypeOf(type) ? Optional.of(DOMAIN_EVENT_TYPE) : Optional.empty();
  }

  @Override
  public List<IdentifierMember> identifiers(Class<?> type) {
    Optional<DomainRole> role = role(type).map(RoleMarker::role);
    if (role.isEmpty()) {
      return List.of();
    }
    return role.get() == DomainRole.AGGREGATE ? identities(type) : LinkMembers.of(type);
  }

  @Override
  public Optional<Marker> commandHandler(Executable member) {
    return commandHandler.annotates(member) || deprecatedCommandHandler.annotates(member)
        ? Optional.of(COMMAND_HANDLER)
        : Optional.empty();
  }

  @Override
  public String describe(DomainRole role) {
    return switch (role) {
      case AGGREGATE -> "jMolecules @AggregateRoot / AggregateRoot";
      case DOMAIN_EVENT -> "jMolecules @DomainEvent / DomainEvent";
      case COMMAND -> COMMAND.display();
    };
  }

  @Override
  public String describeIdentifier(DomainRole role) {
    return role == DomainRole.AGGREGATE
        ? IDENTITY.display() + " member"
        : "jMolecules link member (Association, @Association or Identifier)";
  }

  /**
   * The aggregate's {@code @Identity} members; for an {@code AggregateRoot<T, ID>} without one, its
   * {@code getId()}, wherever it is declared.
   */
  private static List<IdentifierMember> identities(Class<?> type) {
    List<IdentifierMember> identities = annotatedIdentities(type);
    if (identities.isEmpty() && isAggregateRootType(type) && !hasIdentityInSuperclass(type)) {
      return List.of(IdentifierMember.ofAccessor(getId(type), AGGREGATE_ROOT_TYPE));
    }
    return identities;
  }

  private static List<IdentifierMember> annotatedIdentities(Class<?> type) {
    List<IdentifierMember> members = new ArrayList<>();
    for (Field field : type.getDeclaredFields()) {
      if (field.isAnnotationPresent(Identity.class) && !Modifier.isStatic(field.getModifiers())) {
        members.add(IdentifierMember.ofField(field, IDENTITY));
      }
    }
    Set<String> components = componentNames(type);
    for (Method method : type.getDeclaredMethods()) {
      if (method.isAnnotationPresent(Identity.class)
          && !method.isBridge()
          && !components.contains(method.getName())) {
        members.add(IdentifierMember.ofAccessor(method, IDENTITY));
      }
    }
    return List.copyOf(members);
  }

  private static boolean hasIdentityInSuperclass(Class<?> type) {
    for (Class<?> level = type.getSuperclass();
        level != null && level != Object.class;
        level = level.getSuperclass()) {
      if (!annotatedIdentities(level).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** The public {@code getId()} with the most specific return type, the identifier type. */
  private static Method getId(Class<?> type) {
    try {
      return type.getMethod("getId");
    } catch (NoSuchMethodException abstractAggregate) {
      throw new IllegalStateException(type.getName() + " implements AggregateRoot without getId()");
    }
  }

  private static boolean isAggregateRootType(Class<?> type) {
    return AggregateRoot.class.isAssignableFrom(type) && !type.isInterface();
  }

  /** A record component annotated {@code @Identity} counts once, through its field. */
  private static Set<String> componentNames(Class<?> type) {
    RecordComponent[] components = type.getRecordComponents();
    return components == null
        ? Set.of()
        : Arrays.stream(components).map(RecordComponent::getName).collect(Collectors.toSet());
  }

  static Marker marker(String display) {
    return new Marker(NAME, "jMolecules " + display);
  }
}
