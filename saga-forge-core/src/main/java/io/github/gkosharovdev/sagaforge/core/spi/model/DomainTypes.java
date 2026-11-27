package io.github.gkosharovdev.sagaforge.core.spi.model;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypeResolver.RoleMarker;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The enabled vocabularies of domain markers, composed: Saga Forge's annotations first, then each
 * added {@link DomainTypeResolver} in the order given. The scanner, the runtime, the transport and
 * the starter ask this class, never an annotation, which roles, identifiers and command handlers a
 * model has.
 *
 * <p>A type has a role when every vocabulary that marks it agrees on it; when two disagree, the
 * type has none and {@link #conflicts} names the markers (scanner rule R30). Answers are computed
 * once per class and cached. Instances are immutable and thread-safe; a host builds one and hands
 * the same instance to the runtime and the transport.
 */
public final class DomainTypes {

  private static final DomainTypes BUILT_IN = new DomainTypes(List.of(new SagaForgeAnnotations()));

  private final List<DomainTypeResolver> resolvers;
  private final ClassValue<List<RoleMarker>> roleAnswers =
      new ClassValue<>() {
        @Override
        protected List<RoleMarker> computeValue(Class<?> type) {
          return resolvers.stream().flatMap(resolver -> resolver.roles(type).stream()).toList();
        }
      };
  private final ClassValue<List<List<IdentifierMember>>> identifierAnswers =
      new ClassValue<>() {
        @Override
        protected List<List<IdentifierMember>> computeValue(Class<?> type) {
          return resolvers.stream()
              .map(resolver -> List.copyOf(resolver.identifiers(type)))
              .toList();
        }
      };
  private final ClassValue<Optional<IdentifierMember>> valueMembers =
      new ClassValue<>() {
        @Override
        protected Optional<IdentifierMember> computeValue(Class<?> type) {
          for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            List<IdentifierMember> declared = identifiers(c);
            if (!declared.isEmpty()) {
              return Optional.of(declared.get(0));
            }
          }
          return Optional.empty();
        }
      };
  private final Map<Executable, Optional<Marker>> commandHandlers = new ConcurrentHashMap<>();

  private DomainTypes(List<DomainTypeResolver> resolvers) {
    this.resolvers = resolvers;
  }

  /**
   * Saga Forge's own annotations only, the default everywhere a vocabulary can be chosen.
   *
   * @return the shared built-in instance
   */
  public static DomainTypes builtIn() {
    return BUILT_IN;
  }

  /**
   * Saga Forge's own annotations plus {@code added}, in that order.
   *
   * @param added the vocabularies to enable besides the built-in one
   * @return a new instance
   * @throws IllegalArgumentException if {@code added} or an element is null, or a vocabulary name
   *     is used twice (including {@code "Saga Forge"})
   */
  public static DomainTypes of(DomainTypeResolver... added) {
    if (added == null) {
      throw new IllegalArgumentException("resolvers must not be null");
    }
    List<DomainTypeResolver> all = new ArrayList<>(BUILT_IN.resolvers);
    Set<String> names = new HashSet<>(Set.of(SagaForgeAnnotations.NAME));
    for (DomainTypeResolver resolver : added) {
      if (resolver == null) {
        throw new IllegalArgumentException("resolvers must not contain null elements");
      }
      if (!names.add(resolver.name())) {
        throw new IllegalArgumentException(
            "Vocabulary '" + resolver.name() + "' is registered twice");
      }
      all.add(resolver);
    }
    return all.size() == 1 ? BUILT_IN : new DomainTypes(List.copyOf(all));
  }

  /**
   * The names of the enabled vocabularies, Saga Forge's first.
   *
   * @return for example {@code ["Saga Forge", "jMolecules"]}
   */
  public List<String> vocabularies() {
    return resolvers.stream().map(DomainTypeResolver::name).toList();
  }

  /**
   * The role every vocabulary that marks {@code type} agrees on.
   *
   * @param type any class
   * @return the role, or empty if no vocabulary marks the type or two disagree
   */
  public Optional<DomainRole> role(Class<?> type) {
    Set<DomainRole> roles = roles(type);
    return roles.size() == 1 ? Optional.of(roles.iterator().next()) : Optional.empty();
  }

  /**
   * Whether {@code type} is an aggregate.
   *
   * @param type any class
   * @return {@code true} if its role is {@link DomainRole#AGGREGATE}
   */
  public boolean isAggregate(Class<?> type) {
    return hasRole(type, DomainRole.AGGREGATE);
  }

  /**
   * Whether {@code type} is a domain event.
   *
   * @param type any class
   * @return {@code true} if its role is {@link DomainRole#DOMAIN_EVENT}
   */
  public boolean isDomainEvent(Class<?> type) {
    return hasRole(type, DomainRole.DOMAIN_EVENT);
  }

  /**
   * Whether {@code type} is a command.
   *
   * @param type any class
   * @return {@code true} if its role is {@link DomainRole#COMMAND}
   */
  public boolean isCommand(Class<?> type) {
    return hasRole(type, DomainRole.COMMAND);
  }

  /**
   * Every role marker on {@code type}, Saga Forge's first.
   *
   * @param type any class
   * @return the markers, empty if no vocabulary marks the type
   */
  public List<Marker> markers(Class<?> type) {
    return roleAnswers.get(type).stream().map(RoleMarker::marker).toList();
  }

  /**
   * The marker making {@code member} a command handler, the first vocabulary's that marks it.
   *
   * @param member a method or constructor
   * @return the marker, or empty if no vocabulary marks it
   */
  public Optional<Marker> commandHandler(Executable member) {
    return commandHandlers.computeIfAbsent(
        member,
        executable ->
            resolvers.stream()
                .flatMap(resolver -> resolver.commandHandler(executable).stream())
                .findFirst());
  }

  /**
   * The identifier members (of an aggregate) or link members (of an event or command) declared by
   * {@code type} itself, in all vocabularies; a member two vocabularies mark counts once, with the
   * first vocabulary's marker.
   *
   * @param type any class
   * @return the members, in vocabulary order and then declaration order
   */
  public List<IdentifierMember> identifiers(Class<?> type) {
    Set<Member> seen = new HashSet<>();
    List<IdentifierMember> distinct = new ArrayList<>();
    for (List<IdentifierMember> answer : identifierAnswers.get(type)) {
      for (IdentifierMember member : answer) {
        if (seen.add(member.member())) {
          distinct.add(member);
        }
      }
    }
    return List.copyOf(distinct);
  }

  /**
   * The identity held by {@code eventOrCommand}: the value of the first identifier or link member
   * declared by its class or, failing that, the nearest superclass declaring one.
   *
   * @param eventOrCommand an aggregate, event or command
   * @return the identity, never null
   * @throws IllegalStateException if no class in its hierarchy declares such a member, or the value
   *     is null or cannot be read
   */
  public Object identifierValue(Object eventOrCommand) {
    if (eventOrCommand == null) {
      throw new IllegalArgumentException("eventOrCommand must not be null");
    }
    Class<?> type = eventOrCommand.getClass();
    return valueMembers
        .get(type)
        .orElseThrow(() -> new IllegalStateException(type.getName() + " declares no " + noun()))
        .read(eventOrCommand);
  }

  /**
   * The aggregate an event or command concerns, among {@code aggregates}: the one its link member
   * names or, for a link that names none, the only aggregate whose identifier has the link's value
   * type (scanner rule R31).
   *
   * @param eventOrCommandType an event or command type
   * @param aggregates the model's aggregate types; types that are not aggregates are ignored
   * @return {@link AggregateResolution.Resolved} or, with a reason, {@link
   *     AggregateResolution.Unresolved}
   */
  public AggregateResolution aggregateOf(
      Class<?> eventOrCommandType, Collection<Class<?>> aggregates) {
    List<IdentifierMember> links = identifiers(eventOrCommandType);
    if (links.isEmpty()) {
      return new AggregateResolution.Unresolved(
          "it declares no " + describeIdentifier(eventOrCommandType), List.of());
    }
    List<Class<?>> known = aggregates.stream().distinct().filter(this::isAggregate).toList();
    Set<Class<?>> concerned = new LinkedHashSet<>();
    for (IdentifierMember link : links) {
      AggregateResolution resolution = aggregateOf(link, known);
      if (resolution instanceof AggregateResolution.Unresolved) {
        return resolution;
      }
      concerned.add(((AggregateResolution.Resolved) resolution).aggregate());
    }
    if (concerned.size() > 1) {
      List<Class<?>> candidates = bySimpleName(concerned);
      return new AggregateResolution.Unresolved(
          "its members concern different aggregates: " + simpleNames(candidates), candidates);
    }
    return new AggregateResolution.Resolved(concerned.iterator().next(), links.get(0));
  }

  private AggregateResolution aggregateOf(IdentifierMember link, List<Class<?>> known) {
    Optional<Class<?>> named = link.namedAggregate();
    if (named.isPresent()) {
      return isAggregate(named.get())
          ? new AggregateResolution.Resolved(named.get(), link)
          : new AggregateResolution.Unresolved(
              "it names " + named.get().getSimpleName() + ", which is not an aggregate",
              List.of(named.get()));
    }
    List<Class<?>> candidates =
        bySimpleName(
            known.stream()
                .filter(
                    aggregate ->
                        identifiers(aggregate).stream()
                            .anyMatch(identifier -> identifier.valueType() == link.valueType()))
                .toList());
    String identifierType = link.valueType().getSimpleName();
    return switch (candidates.size()) {
      case 0 ->
          new AggregateResolution.Unresolved(
              "no known aggregate has identifier type " + identifierType, List.of());
      case 1 -> new AggregateResolution.Resolved(candidates.get(0), link);
      default ->
          new AggregateResolution.Unresolved(
              "identifier type " + identifierType + " belongs to " + simpleNames(candidates),
              candidates);
    };
  }

  /**
   * How a command type reaches its handler: Saga Forge's {@code @Command(delivery = ...)}, else
   * {@link CommandDelivery#IN_UNIT_OF_WORK}.
   *
   * @param commandType a command type
   * @return its delivery
   */
  public CommandDelivery delivery(Class<?> commandType) {
    return SagaForgeAnnotations.delivery(commandType);
  }

  /**
   * Disagreements between vocabularies about {@code type} (scanner rule R30): two roles, or
   * identifier members that differ.
   *
   * @param type any class
   * @return one text per disagreement, empty when the vocabularies agree
   */
  public List<String> conflicts(Class<?> type) {
    List<String> conflicts = new ArrayList<>();
    roleConflict(type).ifPresent(conflicts::add);
    identifierConflict(type).ifPresent(conflicts::add);
    return List.copyOf(conflicts);
  }

  private Optional<String> roleConflict(Class<?> type) {
    List<RoleMarker> answers = roleAnswers.get(type);
    RoleMarker first = answers.isEmpty() ? null : answers.get(0);
    return answers.stream()
        .filter(answer -> answer.role() != first.role())
        .findFirst()
        .map(
            other ->
                type.getSimpleName()
                    + " is marked "
                    + first.marker().display()
                    + " ("
                    + first.role().label()
                    + ") and "
                    + other.marker().display()
                    + " ("
                    + other.role().label()
                    + "); a type has one role");
  }

  private Optional<String> identifierConflict(Class<?> type) {
    List<List<IdentifierMember>> answers =
        identifierAnswers.get(type).stream().filter(answer -> !answer.isEmpty()).toList();
    if (answers.size() < 2) {
      return Optional.empty();
    }
    List<IdentifierMember> first = answers.get(0);
    Set<Member> firstMembers = members(first);
    for (List<IdentifierMember> other : answers.subList(1, answers.size())) {
      if (!members(other).equals(firstMembers)) {
        IdentifierMember differing =
            other.stream()
                .filter(member -> !firstMembers.contains(member.member()))
                .findFirst()
                .orElse(other.get(0));
        return Optional.of(
            type.getSimpleName()
                + " names different identifiers: "
                + first.get(0).marker().display()
                + " "
                + first.get(0).name()
                + ", "
                + differing.marker().display()
                + " "
                + differing.name());
      }
    }
    return Optional.empty();
  }

  /**
   * The markers accepted for {@code role} in every enabled vocabulary, as error texts list them.
   *
   * @param role the role
   * @return {@code "@DomainEvent"} with the built-in vocabulary only, otherwise for example {@code
   *     "@DomainEvent, or jMolecules @DomainEvent / DomainEvent"}
   */
  public String describe(DomainRole role) {
    return resolvers.stream()
        .map(resolver -> resolver.describe(role))
        .collect(Collectors.joining(", or "));
  }

  /**
   * How error texts name {@code type}: its simple name, followed by its marker in parentheses when
   * the marker is not one of Saga Forge's annotations.
   *
   * @param type any class
   * @return for example {@code "Order"} or {@code "Order (jMolecules @AggregateRoot)"}
   */
  public String displayName(Class<?> type) {
    return preferredMarker(type)
        .filter(marker -> !marker.marker().vocabulary().equals(SagaForgeAnnotations.NAME))
        .map(marker -> type.getSimpleName() + " (" + marker.marker().display() + ")")
        .orElse(type.getSimpleName());
  }

  /**
   * The identifier or link members accepted for {@code type} by the vocabulary that marks it, as
   * error texts name them after "exactly one" or "an".
   *
   * @param type any class
   * @return for example {@code "@AggregateIdentifier field"} or {@code "identifier
   *     (jMolecules @Identity)"}
   */
  public String describeIdentifier(Class<?> type) {
    return preferredMarker(type)
        .map(marker -> resolver(marker.marker()).describeIdentifier(marker.role()))
        .orElseGet(() -> resolvers.get(0).describeIdentifier(DomainRole.AGGREGATE));
  }

  /**
   * The text for an object whose type lacks the marker a call needs.
   *
   * @param type the object's type
   * @param role the role the call needs
   * @return {@code "{Type} is not annotated @DomainEvent"} with the built-in vocabulary only,
   *     otherwise for example {@code "{Type} is not a domain event (accepted: @DomainEvent, or
   *     jMolecules @DomainEvent / DomainEvent)"}
   */
  public String notMarkedMessage(Class<?> type, DomainRole role) {
    if (resolvers.size() == 1) {
      return type.getName() + " is not annotated " + describe(role);
    }
    String article = role == DomainRole.AGGREGATE ? "an " : "a ";
    return type.getName()
        + " is not "
        + article
        + role.label()
        + " (accepted: "
        + describe(role)
        + ")";
  }

  @Override
  public String toString() {
    return "DomainTypes" + vocabularies();
  }

  private Set<DomainRole> roles(Class<?> type) {
    Set<DomainRole> roles = new LinkedHashSet<>();
    roleAnswers.get(type).forEach(answer -> roles.add(answer.role()));
    return roles;
  }

  private boolean hasRole(Class<?> type, DomainRole role) {
    return role(type).filter(role::equals).isPresent();
  }

  private Optional<RoleMarker> preferredMarker(Class<?> type) {
    Optional<DomainRole> role = role(type);
    return roleAnswers.get(type).stream()
        .filter(answer -> role.isEmpty() || answer.role() == role.get())
        .findFirst();
  }

  private DomainTypeResolver resolver(Marker marker) {
    return resolvers.stream()
        .filter(resolver -> resolver.name().equals(marker.vocabulary()))
        .findFirst()
        .orElse(resolvers.get(0));
  }

  private String noun() {
    return resolvers.size() == 1
        ? resolvers.get(0).describeIdentifier(DomainRole.AGGREGATE)
        : "identifier or link member";
  }

  private static Set<Member> members(List<IdentifierMember> identifiers) {
    return identifiers.stream().map(IdentifierMember::member).collect(Collectors.toSet());
  }

  private static List<Class<?>> bySimpleName(Collection<Class<?>> types) {
    return types.stream()
        .sorted(Comparator.comparing((Class<?> type) -> type.getSimpleName()))
        .toList();
  }

  private static String simpleNames(List<Class<?>> types) {
    return types.stream().map(Class::getSimpleName).collect(Collectors.joining(", "));
  }

  /** Which aggregate an event or command concerns, or why none could be chosen. */
  public sealed interface AggregateResolution {

    /**
     * The aggregate was found.
     *
     * @param aggregate the aggregate type
     * @param link the member that links to it
     */
    record Resolved(Class<?> aggregate, IdentifierMember link) implements AggregateResolution {}

    /**
     * No single aggregate could be chosen.
     *
     * @param reason why, as the R31 text continues after a colon
     * @param candidates the aggregates that were in question, by simple name; may be empty
     */
    record Unresolved(String reason, List<Class<?>> candidates) implements AggregateResolution {

      /** Copies the candidates. */
      public Unresolved {
        candidates = List.copyOf(candidates);
      }
    }
  }
}
