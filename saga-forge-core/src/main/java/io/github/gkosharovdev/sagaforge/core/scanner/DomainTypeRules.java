package io.github.gkosharovdev.sagaforge.core.scanner;

import io.github.gkosharovdev.sagaforge.core.spi.model.DomainRole;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes.AggregateResolution;
import io.github.gkosharovdev.sagaforge.core.spi.model.IdentifierMember;
import io.github.gkosharovdev.sagaforge.core.spi.model.Marker;
import java.lang.reflect.Executable;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The rules about domain types, over every enabled vocabulary (specs/016-marker-spi-jmolecules),
 * and the texts that name their markers. With Saga Forge's annotations only, every text is the one
 * the scanner gave before vocabularies existed.
 *
 * <ul>
 *   <li>{@link #validateIdentifiers} — Property 16: exactly one identifier on each aggregate and
 *       domain event, at most one on each command
 *   <li>{@link #validateEventAggregate} — Property 17: a link that names an aggregate names one
 *   <li>{@link #validateVocabularies} — R30: vocabularies agree on a type's role and identifiers;
 *       R31: every event and command of another vocabulary concerns a known aggregate
 * </ul>
 */
final class DomainTypeRules {

  private final DomainTypes domainTypes;

  DomainTypeRules(DomainTypes domainTypes) {
    this.domainTypes = domainTypes;
  }

  /**
   * Property 16. Only members declared by the type count; a record component counts once. Types
   * whose vocabularies disagree about identifiers are reported by R30 instead.
   */
  void validateIdentifiers(Class<?> type, List<String> errors) {
    Optional<DomainRole> role = domainTypes.role(type);
    if (role.isEmpty() || !domainTypes.conflicts(type).isEmpty()) {
      return;
    }
    int count = identifierCount(type);
    if (role.get() != DomainRole.COMMAND && count != 1) {
      errors.add(
          domainTypes.displayName(type)
              + " must declare exactly one "
              + domainTypes.describeIdentifier(type)
              + ", found "
              + count);
    }
    if (role.get() == DomainRole.COMMAND && count > 1) {
      errors.add(
          typeLabel(type)
              + " must declare at most one "
              + domainTypes.describeIdentifier(type)
              + ", found "
              + count);
    }
  }

  /** Property 17: a domain event's link that names an aggregate names a type that is one. */
  void validateEventAggregate(Class<?> type, List<String> errors) {
    if (!domainTypes.isDomainEvent(type)) {
      return;
    }
    namedNonAggregate(type)
        .ifPresent(
            named ->
                errors.add(
                    typeLabel(type)
                        + " names aggregate "
                        + named.getSimpleName()
                        + ", which is not "
                        + marked(DomainRole.AGGREGATE, "annotated ", "an ", "")));
  }

  /**
   * R30 for every type, R31 for each event and command marked by another vocabulary than Saga
   * Forge's (Saga Forge events name their aggregate, checked by Property 17, and its commands never
   * did). Model errors, in the order of {@code types}.
   */
  void validateVocabularies(
      Collection<Class<?>> types, Collection<Class<?>> aggregates, List<String> modelErrors) {
    for (Class<?> type : types) {
      modelErrors.addAll(domainTypes.conflicts(type));
    }
    for (Class<?> type : types) {
      if (needsAggregate(type)
          && domainTypes.aggregateOf(type, aggregates)
              instanceof AggregateResolution.Unresolved unresolved) {
        modelErrors.add(
            domainTypes.displayName(type)
                + " concerns no known aggregate: "
                + unresolved.reason()
                + ". Name the aggregate on the link member, or give the aggregate its own"
                + " identifier type");
      }
    }
  }

  private boolean needsAggregate(Class<?> type) {
    Optional<DomainRole> role = domainTypes.role(type);
    return role.filter(r -> r != DomainRole.AGGREGATE).isPresent()
        && !isSagaForgeMarked(type)
        && !domainTypes.identifiers(type).isEmpty()
        && namedNonAggregate(type).isEmpty();
  }

  /** The number of distinct identifier members the type declares, in all vocabularies. */
  int identifierCount(Class<?> type) {
    return (int)
        domainTypes.identifiers(type).stream().map(IdentifierMember::member).distinct().count();
  }

  /**
   * How a rule names a type with its role marker: {@code "@Command type Ship"} for a Saga Forge
   * marker, {@code "Command type Ship (jMolecules @Command)"} for another vocabulary's.
   */
  String typeLabel(Class<?> type) {
    Optional<DomainRole> role = domainTypes.role(type);
    if (role.isEmpty() || isSagaForgeMarked(type)) {
      return domainTypes.markers(type).stream()
              .findFirst()
              .map(marker -> marker.display() + " type ")
              .orElse("")
          + type.getSimpleName();
    }
    String label = role.get().label();
    return Character.toUpperCase(label.charAt(0))
        + label.substring(1)
        + " type "
        + domainTypes.displayName(type);
  }

  /**
   * A phrase naming the markers accepted for {@code role}: with Saga Forge's annotations only,
   * {@code builtInPrefix + "@Command" + suffix}; otherwise {@code article + "command" + suffix + "
   * (@Command, or jMolecules ...)"}.
   */
  String marked(DomainRole role, String builtInPrefix, String article, String suffix) {
    if (isBuiltInOnly()) {
      return builtInPrefix + domainTypes.describe(role) + suffix;
    }
    return article + role.label() + suffix + " (" + domainTypes.describe(role) + ")";
  }

  /**
   * {@code phrase} with its indefinite article: {@code "an @AggregateIdentifier field"}, {@code "a
   * jMolecules link member"}. The article follows the first letter after a leading {@code @}.
   */
  static String withArticle(String phrase) {
    String word = phrase.startsWith("@") ? phrase.substring(1) : phrase;
    boolean vowel = !word.isEmpty() && "aeiouAEIOU".indexOf(word.charAt(0)) >= 0;
    return (vowel ? "an " : "a ") + phrase;
  }

  /** How a rule names command handlers: {@code "@CommandHandler"}, or {@code "command handler"}. */
  String commandHandlerLabel() {
    return isBuiltInOnly() ? "@CommandHandler" : "command handler";
  }

  /** How a rule names the marker of {@code member}, a command handler. */
  String commandHandlerMarker(Executable member) {
    return domainTypes.commandHandler(member).map(Marker::display).orElse("@CommandHandler");
  }

  /** How a rule names an aggregate host's marker: {@code "@Aggregate"} or the vocabulary's. */
  String aggregateMarker(Class<?> host) {
    return domainTypes.markers(host).stream().findFirst().map(Marker::display).orElse("@Aggregate");
  }

  private Optional<Class<?>> namedNonAggregate(Class<?> type) {
    return domainTypes.identifiers(type).stream()
        .map(IdentifierMember::namedAggregate)
        .flatMap(Optional::stream)
        .filter(named -> !domainTypes.isAggregate(named))
        .min(Comparator.comparing(Class::getName));
  }

  private boolean isSagaForgeMarked(Class<?> type) {
    return domainTypes.markers(type).stream()
        .findFirst()
        .filter(marker -> marker.vocabulary().equals(builtInVocabulary()))
        .isPresent();
  }

  private boolean isBuiltInOnly() {
    return domainTypes.vocabularies().size() == 1;
  }

  private static String builtInVocabulary() {
    return DomainTypes.builtIn().vocabularies().get(0);
  }
}
