package io.github.gkosharovdev.sagaforge.core.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes.AggregateResolution.Resolved;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes.AggregateResolution.Unresolved;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestAggregate;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestCommand;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestDomainTypeResolver;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestEvent;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestHandles;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestId;
import java.lang.reflect.Executable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link DomainTypes} over the built-in and the test vocabulary (specs/016 R30, R31). */
class DomainTypesTest {

  record ParcelId(String value) {}

  record CrateId(String value) {}

  @TestAggregate
  static final class Parcel {
    @TestId ParcelId id;

    @TestHandles
    void handle(Ship command) {}
  }

  @TestAggregate
  static final class Crate {
    @TestId CrateId id;
  }

  @TestAggregate
  static final class Pallet {
    @TestId ParcelId id;
  }

  @TestCommand
  record Ship(@TestId ParcelId parcel) {}

  @TestEvent
  record ParcelShipped(@TestId ParcelId parcel) {}

  @TestEvent(of = Parcel.class)
  record ParcelLost(@TestId String parcelId) {}

  @TestEvent
  record Orphaned(@TestId UUID id) {}

  @TestEvent(of = String.class)
  record PointsElsewhere(@TestId String id) {}

  @TestEvent
  record TwoLinks(@TestId ParcelId parcel, @TestId CrateId crate) {}

  @TestEvent
  record Unlinked(String note) {}

  @TestAggregate
  @DomainEvent(aggregate = Parcel.class)
  record TwoRoles(@AggregateIdentifier String id) {}

  @Aggregate
  @TestAggregate
  static final class TwoIdentifiers {
    @AggregateIdentifier String first;
    @TestId String second;
  }

  @Aggregate
  @TestAggregate
  static final class SameIdentifier {
    @AggregateIdentifier @TestId String id;
  }

  abstract static class BaseEvent {
    @TestId final String id;

    BaseEvent(String id) {
      this.id = id;
    }
  }

  @TestEvent(of = Parcel.class)
  static final class SubEvent extends BaseEvent {
    SubEvent(String id) {
      super(id);
    }
  }

  private final DomainTypes types = DomainTypes.of(new TestDomainTypeResolver());

  @Nested
  class Roles {

    @Test
    @DisplayName("each vocabulary's markers give their roles; the union is answered")
    void union() {
      assertThat(types.role(Parcel.class)).contains(DomainRole.AGGREGATE);
      assertThat(types.isDomainEvent(ParcelShipped.class)).isTrue();
      assertThat(types.isCommand(Ship.class)).isTrue();
      assertThat(types.role(String.class)).isEmpty();
      assertThat(DomainTypes.builtIn().role(Parcel.class)).isEmpty();
    }

    @Test
    @DisplayName("two vocabularies marking the same role and member agree")
    void agreement() {
      assertThat(types.role(SameIdentifier.class)).contains(DomainRole.AGGREGATE);
      assertThat(types.identifiers(SameIdentifier.class))
          .singleElement()
          .satisfies(
              member -> assertThat(member.marker().display()).isEqualTo("@AggregateIdentifier"));
      assertThat(types.conflicts(SameIdentifier.class)).isEmpty();
      assertThat(types.markers(SameIdentifier.class))
          .extracting(Marker::display)
          .containsExactly("@Aggregate", "@TestAggregate");
    }

    @Test
    @DisplayName("R30: two roles leave the type with none and name both markers")
    void twoRoles() {
      assertThat(types.role(TwoRoles.class)).isEmpty();
      assertThat(types.conflicts(TwoRoles.class))
          .containsExactly(
              "TwoRoles is marked @DomainEvent (domain event) and @TestAggregate (aggregate);"
                  + " a type has one role");
    }

    @Test
    @DisplayName("R30: vocabularies naming different identifier members are named")
    void twoIdentifiers() {
      assertThat(types.conflicts(TwoIdentifiers.class))
          .containsExactly(
              "TwoIdentifiers names different identifiers: @AggregateIdentifier first, @TestId"
                  + " second");
    }

    @Test
    @DisplayName("command handlers come from any vocabulary")
    void commandHandlers() throws Exception {
      Executable handler = Parcel.class.getDeclaredMethod("handle", Ship.class);

      assertThat(types.commandHandler(handler)).map(Marker::display).contains("@TestHandles");
      assertThat(DomainTypes.builtIn().commandHandler(handler)).isEmpty();
    }
  }

  @Nested
  class Composition {

    @Test
    @DisplayName("vocabularies are listed built-in first and a name may be used once")
    void names() {
      assertThat(types.vocabularies()).containsExactly("Saga Forge", "Test");
      assertThatThrownBy(
              () -> DomainTypes.of(new TestDomainTypeResolver(), new TestDomainTypeResolver()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Vocabulary 'Test' is registered twice");
      assertThatThrownBy(() -> DomainTypes.of(renamed("Saga Forge")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Vocabulary 'Saga Forge' is registered twice");
      assertThatThrownBy(() -> DomainTypes.of((DomainTypeResolver) null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("texts list every vocabulary's markers and name a non-built-in marker")
    void texts() {
      assertThat(types.describe(DomainRole.DOMAIN_EVENT)).isEqualTo("@DomainEvent, or @TestEvent");
      assertThat(types.notMarkedMessage(String.class, DomainRole.DOMAIN_EVENT))
          .isEqualTo(
              "java.lang.String is not a domain event (accepted: @DomainEvent, or @TestEvent)");
      assertThat(types.notMarkedMessage(String.class, DomainRole.AGGREGATE))
          .startsWith("java.lang.String is not an aggregate");
      assertThat(types.displayName(Parcel.class)).isEqualTo("Parcel (@TestAggregate)");
      assertThat(types.displayName(SameIdentifier.class)).isEqualTo("SameIdentifier");
      assertThat(types.describeIdentifier(Parcel.class)).isEqualTo("@TestId member");
      assertThat(types.describeIdentifier(SameIdentifier.class))
          .isEqualTo("@AggregateIdentifier field");
    }

    @Test
    @DisplayName("each answer is computed once per class")
    void caching() {
      AtomicInteger calls = new AtomicInteger();
      DomainTypes counted = DomainTypes.of(counting(calls));

      for (int i = 0; i < 5; i++) {
        counted.role(Parcel.class);
        counted.markers(Parcel.class);
        counted.conflicts(Parcel.class);
      }

      assertThat(calls).hasValue(1);
    }
  }

  @Nested
  class AggregateOf {

    private final List<Class<?>> aggregates = List.of(Parcel.class, Crate.class, String.class);

    @Test
    @DisplayName("a link naming an aggregate resolves to it")
    void named() {
      assertThat(types.aggregateOf(ParcelLost.class, aggregates))
          .isInstanceOfSatisfying(
              Resolved.class, resolved -> assertThat(resolved.aggregate()).isEqualTo(Parcel.class));
    }

    @Test
    @DisplayName("a link naming none resolves by its identifier type")
    void byIdentifierType() {
      assertThat(types.aggregateOf(ParcelShipped.class, aggregates))
          .isInstanceOfSatisfying(
              Resolved.class,
              resolved -> {
                assertThat(resolved.aggregate()).isEqualTo(Parcel.class);
                assertThat(resolved.link().name()).isEqualTo("parcel");
              });
      assertThat(types.aggregateOf(Ship.class, aggregates)).isInstanceOf(Resolved.class);
    }

    @Test
    @DisplayName("no aggregate with the identifier type is unresolved")
    void none() {
      assertThat(types.aggregateOf(Orphaned.class, aggregates))
          .isEqualTo(new Unresolved("no known aggregate has identifier type UUID", List.of()));
      assertThat(types.aggregateOf(Unlinked.class, aggregates))
          .isEqualTo(new Unresolved("it declares no @TestId member", List.of()));
    }

    @Test
    @DisplayName("an identifier type two aggregates share is unresolved, naming both")
    void shared() {
      assertThat(types.aggregateOf(ParcelShipped.class, List.of(Pallet.class, Parcel.class)))
          .isEqualTo(
              new Unresolved(
                  "identifier type ParcelId belongs to Pallet, Parcel",
                  List.of(Pallet.class, Parcel.class)));
    }

    @Test
    @DisplayName("a link naming a type that is not an aggregate is unresolved")
    void namedNonAggregate() {
      assertThat(types.aggregateOf(PointsElsewhere.class, aggregates))
          .isEqualTo(
              new Unresolved("it names String, which is not an aggregate", List.of(String.class)));
    }

    @Test
    @DisplayName("links that concern different aggregates are unresolved")
    void disagreeing() {
      assertThat(types.aggregateOf(TwoLinks.class, aggregates))
          .isEqualTo(
              new Unresolved(
                  "its members concern different aggregates: Crate, Parcel",
                  List.of(Crate.class, Parcel.class)));
    }
  }

  @Nested
  class IdentifierValue {

    @Test
    @DisplayName("the identity is read from the class or its nearest superclass declaring one")
    void throughSuperclass() {
      assertThat(types.identifierValue(new ParcelShipped(new ParcelId("p-1"))))
          .isEqualTo(new ParcelId("p-1"));
      assertThat(types.identifierValue(new SubEvent("s-1"))).isEqualTo("s-1");
    }

    @Test
    @DisplayName("an object without identifier is reported naming its type")
    void missing() {
      assertThatThrownBy(() -> DomainTypes.builtIn().identifierValue(new Unlinked("n")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(Unlinked.class.getName() + " declares no @AggregateIdentifier field");
    }
  }

  private static DomainTypeResolver renamed(String name) {
    return new Delegating(new TestDomainTypeResolver()) {
      @Override
      public String name() {
        return name;
      }
    };
  }

  private static DomainTypeResolver counting(AtomicInteger calls) {
    return new Delegating(new TestDomainTypeResolver()) {
      @Override
      public Optional<RoleMarker> role(Class<?> type) {
        calls.incrementAndGet();
        return super.role(type);
      }
    };
  }

  /** Forwards to a resolver, so a test can change one answer. */
  private static class Delegating implements DomainTypeResolver {
    private final DomainTypeResolver delegate;

    Delegating(DomainTypeResolver delegate) {
      this.delegate = delegate;
    }

    @Override
    public String name() {
      return delegate.name();
    }

    @Override
    public Optional<RoleMarker> role(Class<?> type) {
      return delegate.role(type);
    }

    @Override
    public List<IdentifierMember> identifiers(Class<?> type) {
      return delegate.identifiers(type);
    }

    @Override
    public Optional<Marker> commandHandler(Executable member) {
      return delegate.commandHandler(member);
    }

    @Override
    public String describe(DomainRole role) {
      return delegate.describe(role);
    }

    @Override
    public String describeIdentifier(DomainRole role) {
      return delegate.describeIdentifier(role);
    }
  }
}
