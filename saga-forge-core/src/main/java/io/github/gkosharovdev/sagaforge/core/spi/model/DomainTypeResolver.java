package io.github.gkosharovdev.sagaforge.core.spi.model;

import java.lang.reflect.Executable;
import java.util.List;
import java.util.Optional;

/**
 * One vocabulary of domain markers: answers, per type and member, which role a type plays, which
 * members identify it or link it to an aggregate, and which executables handle commands.
 *
 * <p>A resolver knows nothing about the model it is used in; {@link DomainTypes} composes the
 * enabled resolvers and is what Saga Forge asks. An empty answer means "this vocabulary does not
 * mark it", never "it is not one". Implementations are stateless or immutable, and thread-safe;
 * Saga Forge asks each question once per class and caches the answer, so a resolver must answer the
 * same way every time.
 *
 * <p>Saga Forge's own annotations are the built-in vocabulary, always enabled. The {@code
 * saga-forge-jmolecules} module adds jMolecules'; an application may add its own.
 */
public interface DomainTypeResolver {

  /**
   * The role a type has in this vocabulary, with the marker that gives it.
   *
   * @param role the role
   * @param marker the marker giving it, as error texts name it
   */
  record RoleMarker(DomainRole role, Marker marker) {

    /**
     * Checks both components.
     *
     * @throws IllegalArgumentException if a component is null
     */
    public RoleMarker {
      if (role == null || marker == null) {
        throw new IllegalArgumentException("role and marker must not be null");
      }
    }
  }

  /**
   * The vocabulary's name, unique among the enabled vocabularies.
   *
   * @return a human-readable name, for example {@code "jMolecules"}
   */
  String name();

  /**
   * The role this vocabulary gives {@code type}.
   *
   * @param type any class
   * @return the role with its marker, or empty if this vocabulary does not mark the type
   */
  Optional<RoleMarker> role(Class<?> type);

  /**
   * Every role this vocabulary's markers give {@code type}, the one {@link #role} returns first. A
   * type a vocabulary marks with two roles is a modelling error that validation reports (scanner
   * rule R30); a vocabulary whose markers cannot overlap keeps the default.
   *
   * @param type any class
   * @return the roles with their markers, at most one per role; empty if none
   */
  default List<RoleMarker> roles(Class<?> type) {
    return role(type).stream().toList();
  }

  /**
   * The members of {@code type} this vocabulary marks as identifying it (an aggregate) or as
   * linking it to the aggregate it concerns (an event or a command).
   *
   * @param type any class
   * @return members declared by {@code type} itself, in declaration order; empty if none
   */
  List<IdentifierMember> identifiers(Class<?> type);

  /**
   * The marker making {@code member} a command handler, if this vocabulary marks it.
   *
   * @param member a method or constructor
   * @return the marker, or empty
   */
  Optional<Marker> commandHandler(Executable member);

  /**
   * The markers this vocabulary accepts for {@code role}, as error texts list them when a marker is
   * missing.
   *
   * @param role the role
   * @return for example {@code "@DomainEvent"} or {@code "jMolecules @DomainEvent / DomainEvent"}
   */
  String describe(DomainRole role);

  /**
   * The members this vocabulary accepts as the identifier (of an aggregate) or link (of an event or
   * command), as error texts name them after "exactly one" or "an".
   *
   * @param role the role of the type that lacks it
   * @return for example {@code "@AggregateIdentifier field"} or {@code "identifier
   *     (jMolecules @Identity)"}
   */
  String describeIdentifier(DomainRole role);
}
