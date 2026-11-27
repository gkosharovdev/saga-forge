package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestAggregate;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestCommand;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestDomainTypeResolver;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestEvent;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestHandles;
import io.github.gkosharovdev.sagaforge.core.spi.model.vocabulary.TestId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scanner's rules over a vocabulary other than Saga Forge's (specs/016-marker-spi-jmolecules
 * US3, US4): every rule fires naming the marker used, R30 and R31 catch what only vocabularies can
 * get wrong, and a model mixing vocabularies validates.
 */
class VocabularyRulesTest {

  record ParcelId(String value) {}

  @TestAggregate
  static final class Parcel {
    @TestId ParcelId id;

    @TestHandles
    Parcel(Register command) {
      this.id = command.parcel();
    }

    @TestHandles
    void handle(Ship command) {}
  }

  @TestAggregate
  static final class Pallet {
    @TestId ParcelId id;
  }

  @TestCommand
  record Register(ParcelId parcel) {}

  @TestCommand
  record Ship(@TestId ParcelId parcel) {}

  @TestEvent
  record ParcelShipped(@TestId ParcelId parcel) {}

  @TestEvent(of = Parcel.class)
  record ParcelDelivered(@TestId String parcel) {}

  @Saga
  static final class ShippingSaga {
    @SagaId String sagaId;
    CommandGateway commands;

    @StartSaga
    @SagaStep(associationProperty = "parcel")
    void shipped(ParcelShipped event) {}

    @EndSaga
    @SagaStep(associationProperty = "parcel")
    void delivered(ParcelDelivered event) {}
  }

  @TestAggregate
  static final class Unidentified {
    @TestHandles
    void handle(Ship command) {}
  }

  @TestCommand
  record Redirect(@TestId ParcelId parcel, @TestId String reason) {}

  static final class RedirectHandler {
    @TestHandles
    void handle(Redirect command) {}
  }

  @TestEvent
  record ParcelLost(@TestId UUID trackingId) {}

  @TestEvent(of = String.class)
  record ParcelMisrouted(@TestId String parcel) {}

  @Saga
  static final class LossSaga {
    @SagaId String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "trackingId")
    void lost(ParcelLost event) {}

    @EndSaga
    @SagaStep(associationProperty = "parcel")
    void misrouted(ParcelMisrouted event) {}
  }

  @TestAggregate
  @DomainEvent(aggregate = Parcel.class)
  record Confused(@AggregateIdentifier String id) {}

  @Aggregate
  @TestAggregate
  static final class DoublyIdentified {
    @AggregateIdentifier String first;
    @TestId String second;
  }

  static final class Repository {
    @io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader
    Parcel load(ParcelId id) {
      return null;
    }

    @io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver
    void save(Confused confused) {}
  }

  static final class Malformed {
    @TestHandles
    void handle(String notACommand) {}

    @io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader
    String load(ParcelId id) {
      return null;
    }
  }

  private final ReflectiveAnnotationScanner scanner =
      new ReflectiveAnnotationScanner(DomainTypes.of(new TestDomainTypeResolver()));

  @Test
  @DisplayName("a model mixing Saga Forge's saga annotations with another vocabulary validates")
  void mixedModel() {
    ValidationReport report = scanner.validateAll(List.of(ShippingSaga.class, Parcel.class));

    assertThat(report.allErrors()).isEmpty();
  }

  @Test
  @DisplayName("the same model is not a model without the vocabulary")
  void unknownVocabulary() {
    ValidationReport report =
        new ReflectiveAnnotationScanner().validateAll(List.of(ShippingSaga.class));

    assertThat(report.allErrors())
        .contains(
            "@SagaStep method shipped parameter type ParcelShipped must be annotated with"
                + " @DomainEvent");
  }

  @Test
  @DisplayName("missing markers list every vocabulary's")
  void missingMarker() {
    @Saga
    final class StringSaga {
      @SagaId String sagaId;

      @StartSaga
      @EndSaga
      @SagaStep(associationProperty = "length")
      void on(String event) {}
    }

    assertThat(scanner.validate(StringSaga.class).errors())
        .contains(
            "@SagaStep method on parameter type String must be a domain event (@DomainEvent, or"
                + " @TestEvent)");
  }

  @Test
  @DisplayName("identifier rules name the vocabulary's markers (Property 16)")
  void identifiers() {
    ValidationReport report =
        scanner.validateAll(List.of(Unidentified.class, RedirectHandler.class));

    assertThat(report.allErrors())
        .contains(
            "Unidentified (@TestAggregate) must declare exactly one @TestId member, found 0",
            "Command type Redirect (@TestCommand) must declare at most one @TestId member, found 2");
  }

  @Test
  @DisplayName(
      "R31: an event concerning no known aggregate; Property 17: one naming a non-aggregate")
  void unresolvedAggregates() {
    ValidationReport report = scanner.validateAll(List.of(LossSaga.class, Parcel.class));

    assertThat(report.allErrors())
        .containsExactlyInAnyOrder(
            "ParcelLost (@TestEvent) concerns no known aggregate: no known aggregate has"
                + " identifier type UUID. Name the aggregate on the link member, or give the"
                + " aggregate its own identifier type",
            "Domain event type ParcelMisrouted (@TestEvent) names aggregate String, which is not"
                + " an aggregate (@Aggregate, or @TestAggregate)");
  }

  @Test
  @DisplayName("R31: an identifier type two aggregates share names both")
  void sharedIdentifierType() {
    ValidationReport report =
        scanner.validateAll(List.of(ShippingSaga.class, Parcel.class, Pallet.class));

    assertThat(report.modelErrors())
        .contains(
            "ParcelShipped (@TestEvent) concerns no known aggregate: identifier type ParcelId"
                + " belongs to Pallet, Parcel. Name the aggregate on the link member, or give the"
                + " aggregate its own identifier type");
  }

  @Test
  @DisplayName("R30: two roles, and identifiers that differ between vocabularies")
  void conflicts() {
    ValidationReport report =
        scanner.validateAll(List.of(Repository.class, DoublyIdentified.class, Parcel.class));

    assertThat(report.modelErrors())
        .containsExactlyInAnyOrder(
            "Confused is marked @DomainEvent (domain event) and @TestAggregate (aggregate); a type"
                + " has one role",
            "DoublyIdentified names different identifiers: @AggregateIdentifier first, @TestId"
                + " second");
  }

  @Test
  @DisplayName("handler and loader rules name the vocabulary's handler and accepted markers")
  void handlerAndLoaderTexts() {
    assertThat(scanner.validateAll(List.of(Malformed.class)).allErrors())
        .contains(
            "@TestHandles method handle in Malformed must declare exactly one command parameter"
                + " (@Command, or @TestCommand), found 0",
            "@AggregateLoader method load in Malformed must take exactly one parameter and return an"
                + " aggregate type (@Aggregate, or @TestAggregate)");
  }
}
