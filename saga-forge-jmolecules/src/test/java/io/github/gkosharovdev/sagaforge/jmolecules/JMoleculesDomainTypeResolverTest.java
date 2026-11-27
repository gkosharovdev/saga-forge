package io.github.gkosharovdev.sagaforge.jmolecules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.spi.model.DomainRole;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypeResolver.RoleMarker;
import io.github.gkosharovdev.sagaforge.core.spi.model.IdentifierMember;
import io.github.gkosharovdev.sagaforge.core.spi.model.Marker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jmolecules.architecture.cqrs.Command;
import org.jmolecules.architecture.cqrs.CommandHandler;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Association;
import org.jmolecules.ddd.annotation.Identity;
import org.jmolecules.ddd.types.Identifier;
import org.jmolecules.event.annotation.DomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Every row of the mapping tables in contracts/jmolecules-module.md. */
class JMoleculesDomainTypeResolverTest {

  record ParcelId(String value) implements Identifier {}

  @AggregateRoot
  static final class AnnotatedParcel {
    @Identity ParcelId id;
  }

  @AggregateRoot
  static final class AccessorParcel {
    private final ParcelId id = new ParcelId("p-1");

    @Identity
    ParcelId id() {
      return id;
    }
  }

  @AggregateRoot
  record RecordParcel(@Identity ParcelId id) {}

  static final class TypedParcel
      implements org.jmolecules.ddd.types.AggregateRoot<TypedParcel, ParcelId> {
    @Override
    public ParcelId getId() {
      return new ParcelId("p-1");
    }
  }

  static final class TypedParcelWithIdentity
      implements org.jmolecules.ddd.types.AggregateRoot<TypedParcelWithIdentity, ParcelId> {
    @Identity ParcelId key = new ParcelId("p-1");

    @Override
    public ParcelId getId() {
      return key;
    }
  }

  @DomainEvent
  record AnnotatedEvent(ParcelId parcel) {}

  record TypedEvent(ParcelId parcel) implements org.jmolecules.event.types.DomainEvent {}

  @Command
  record Ship(ParcelId parcel) {}

  @SuppressWarnings("deprecation")
  @org.jmolecules.architecture.cqrs.annotation.Command
  record DeprecatedShip(ParcelId parcel) {}

  @DomainEvent
  record Associated(org.jmolecules.ddd.types.Association<TypedParcel, ParcelId> parcel) {}

  @DomainEvent
  @SuppressWarnings("rawtypes")
  record RawAssociated(org.jmolecules.ddd.types.Association parcel) {}

  @DomainEvent
  record NamedAssociation(@Association(aggregateType = TypedParcel.class) String parcel) {}

  @DomainEvent
  record TypeAssociation(@Association ParcelId parcel) {}

  @DomainEvent
  record PlainIds(UUID trackingId, String parcel) {}

  static final class Handlers {
    @CommandHandler
    Handlers(Ship command) {}

    @CommandHandler
    void handle(Ship command) {}

    @SuppressWarnings("deprecation")
    @org.jmolecules.architecture.cqrs.annotation.CommandHandler
    void handleDeprecated(Ship command) {}

    void notAHandler(Ship command) {}
  }

  private final JMoleculesDomainTypeResolver resolver = new JMoleculesDomainTypeResolver();

  private Optional<DomainRole> roleOf(Class<?> type) {
    return resolver.role(type).map(RoleMarker::role);
  }

  private List<String> identifierNames(Class<?> type) {
    return resolver.identifiers(type).stream().map(IdentifierMember::name).toList();
  }

  private IdentifierMember only(Class<?> type) {
    assertThat(resolver.identifiers(type)).hasSize(1);
    return resolver.identifiers(type).get(0);
  }

  @Test
  @DisplayName("the vocabulary is named jMolecules and every display says so")
  void name() {
    assertThat(resolver.name()).isEqualTo("jMolecules");
    assertThat(resolver.role(TypedParcel.class))
        .map(RoleMarker::marker)
        .map(Marker::display)
        .contains("jMolecules AggregateRoot<T, ID>");
    assertThat(resolver.describe(DomainRole.DOMAIN_EVENT))
        .isEqualTo("jMolecules @DomainEvent / DomainEvent");
  }

  @Nested
  @DisplayName("aggregates")
  class Aggregates {

    @Test
    @DisplayName("@AggregateRoot with an @Identity field, accessor or record component")
    void annotated() {
      assertThat(roleOf(AnnotatedParcel.class)).contains(DomainRole.AGGREGATE);
      assertThat(identifierNames(AnnotatedParcel.class)).containsExactly("id");
      assertThat(identifierNames(AccessorParcel.class)).containsExactly("id()");
      assertThat(identifierNames(RecordParcel.class)).containsExactly("id");
    }

    @Test
    @DisplayName("AggregateRoot<T, ID> is identified by getId(), or by @Identity when present")
    void typed() {
      assertThat(roleOf(TypedParcel.class)).contains(DomainRole.AGGREGATE);
      IdentifierMember getId = only(TypedParcel.class);
      assertThat(getId.name()).isEqualTo("getId()");
      assertThat(getId.valueType()).isEqualTo(ParcelId.class);
      assertThat(getId.read(new TypedParcel())).isEqualTo(new ParcelId("p-1"));
      assertThat(identifierNames(TypedParcelWithIdentity.class)).containsExactly("key");
    }
  }

  @Nested
  @DisplayName("events and commands")
  class Messages {

    @Test
    @DisplayName("@DomainEvent, DomainEvent, @Command and the deprecated @Command")
    void roles() {
      assertThat(roleOf(AnnotatedEvent.class)).contains(DomainRole.DOMAIN_EVENT);
      assertThat(roleOf(TypedEvent.class)).contains(DomainRole.DOMAIN_EVENT);
      assertThat(roleOf(Ship.class)).contains(DomainRole.COMMAND);
      assertThat(roleOf(DeprecatedShip.class)).contains(DomainRole.COMMAND);
      assertThat(roleOf(ParcelId.class)).isEmpty();
    }

    @Test
    @DisplayName("an Identifier-typed member is a link resolved by its type")
    void identifierLink() {
      IdentifierMember link = only(TypedEvent.class);
      assertThat(link.valueType()).isEqualTo(ParcelId.class);
      assertThat(link.namedAggregate()).isEmpty();
      assertThat(only(Ship.class).name()).isEqualTo("parcel");
    }

    @Test
    @DisplayName("Association<A, ID> names A and reads the id through getId()")
    void association() {
      IdentifierMember link = only(Associated.class);
      assertThat(link.namedAggregate()).contains(TypedParcel.class);
      assertThat(link.valueType()).isEqualTo(ParcelId.class);
      ParcelId id = new ParcelId("p-7");
      assertThat(link.read(new Associated(org.jmolecules.ddd.types.Association.forId(id))))
          .isEqualTo(id);
    }

    @Test
    @DisplayName("a raw Association is a link naming no aggregate, which R31 reports")
    void rawAssociation() {
      IdentifierMember link = only(RawAssociated.class);
      assertThat(link.namedAggregate()).isEmpty();
      assertThat(link.valueType()).isEqualTo(Identifier.class);
    }

    @Test
    @DisplayName("@Association names its aggregateType, or none")
    void associationAnnotation() {
      assertThat(only(NamedAssociation.class).namedAggregate()).contains(TypedParcel.class);
      assertThat(only(TypeAssociation.class).namedAggregate()).isEmpty();
    }

    @Test
    @DisplayName("members of other types are never links (G-016-05)")
    void plainMembers() {
      assertThat(resolver.identifiers(PlainIds.class)).isEmpty();
    }

    @Test
    @DisplayName("types this vocabulary does not mark have no identifiers here")
    void unmarked() {
      assertThat(resolver.identifiers(ParcelId.class)).isEmpty();
    }
  }

  @Test
  @DisplayName("@CommandHandler on a constructor or method, and the deprecated one")
  void commandHandlers() throws NoSuchMethodException {
    assertThat(resolver.commandHandler(Handlers.class.getDeclaredConstructor(Ship.class)))
        .isPresent();
    assertThat(resolver.commandHandler(Handlers.class.getDeclaredMethod("handle", Ship.class)))
        .map(Marker::display)
        .contains("jMolecules @CommandHandler");
    assertThat(
            resolver.commandHandler(
                Handlers.class.getDeclaredMethod("handleDeprecated", Ship.class)))
        .isPresent();
    assertThat(resolver.commandHandler(Handlers.class.getDeclaredMethod("notAHandler", Ship.class)))
        .isEmpty();
  }

  @Test
  @DisplayName("without jmolecules-events and the CQRS artifact, no event or command is recognised")
  void optionalArtifactsAbsent() throws NoSuchMethodException {
    ClassLoader withoutOptionals =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.jmolecules.event.")
                || name.startsWith("org.jmolecules.architecture.cqrs.")) {
              throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
          }
        };
    JMoleculesDomainTypeResolver ddOnly = new JMoleculesDomainTypeResolver(withoutOptionals);

    assertThat(ddOnly.role(TypedEvent.class)).isEmpty();
    assertThat(ddOnly.role(AnnotatedEvent.class)).isEmpty();
    assertThat(ddOnly.role(Ship.class)).isEmpty();
    assertThat(ddOnly.commandHandler(Handlers.class.getDeclaredMethod("handle", Ship.class)))
        .isEmpty();
    assertThat(ddOnly.role(TypedParcel.class)).map(RoleMarker::role).contains(DomainRole.AGGREGATE);
  }
}
