package io.github.gkosharovdev.sagaforge.jmolecules;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandDelivery;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.ReflectiveAnnotationScanner;
import io.github.gkosharovdev.sagaforge.core.scanner.ValidationReport;
import io.github.gkosharovdev.sagaforge.core.spi.model.DomainTypes;
import java.util.List;
import org.jmolecules.architecture.cqrs.Command;
import org.jmolecules.architecture.cqrs.CommandHandler;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Association;
import org.jmolecules.ddd.annotation.Identity;
import org.jmolecules.ddd.types.Identifier;
import org.jmolecules.event.types.DomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scanner's rules over jMolecules markers (specs/016-marker-spi-jmolecules SC-004, FR-016,
 * FR-017, FR-028): texts name the jMolecules marker, R31 catches events concerning no known
 * aggregate and an association member fixes it, and Saga Forge's outbox delivery stands beside
 * jMolecules' {@code @Command}.
 */
class JMoleculesScannerRulesTest {

  record ParcelId(String value) implements Identifier {}

  @AggregateRoot
  static final class Parcel {
    @Identity ParcelId id;

    @CommandHandler
    void handle(Ship command) {}
  }

  @AggregateRoot
  static final class Pallet {
    @Identity ParcelId id;
  }

  @AggregateRoot
  static final class Unidentified {}

  record ParcelShipped(ParcelId parcel) implements DomainEvent {}

  record ParcelLoaded(@Association(aggregateType = Parcel.class) ParcelId parcel)
      implements DomainEvent {}

  record ParcelTwice(ParcelId first, ParcelId second) implements DomainEvent {}

  @Command
  record Ship(ParcelId parcel) {}

  @Command
  @io.github.gkosharovdev.sagaforge.core.annotation.Command(delivery = CommandDelivery.OUTBOX)
  record Notify(String parcel) {}

  static final class Notifier {
    @CommandHandler
    void handle(Notify command) {}
  }

  @Saga
  static final class ShippingSaga {
    @SagaId String sagaId;

    @StartSaga
    @SagaStep(associationProperty = "parcel")
    void shipped(ParcelShipped event) {}

    @EndSaga
    @SagaStep(associationProperty = "parcel")
    void loaded(ParcelLoaded event) {}
  }

  @Saga
  static final class TwiceSaga {
    @SagaId String sagaId;

    @StartSaga
    @EndSaga
    @SagaStep(associationProperty = "first")
    void twice(ParcelTwice event) {}
  }

  @Saga
  static final class HandlingSaga {
    @SagaId String sagaId;

    @StartSaga
    @EndSaga
    @SagaStep(associationProperty = "parcel")
    void shipped(ParcelShipped event) {}

    @CommandHandler
    void handle(Ship command) {}
  }

  static final class NoCommandHandler {
    @CommandHandler
    void handle(String notACommand) {}
  }

  static final class SecondShipper {
    @CommandHandler
    void handle(Ship command) {}
  }

  @Command
  @io.github.gkosharovdev.sagaforge.core.annotation.Command(delivery = CommandDelivery.OUTBOX)
  record Unhandled(String parcel) {}

  @Command
  record Untargeted(String reason) {}

  @AggregateRoot
  static final class Crate {
    @Identity ParcelId id;

    @CommandHandler
    void handle(Untargeted command) {}
  }

  static final class Repository {
    @io.github.gkosharovdev.sagaforge.core.annotation.AggregateLoader
    String load(ParcelId id) {
      return null;
    }

    @io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver
    void save(String notAnAggregate) {}
  }

  @Saga
  static final class StringSaga {
    @SagaId String sagaId;

    @StartSaga
    @EndSaga
    @SagaStep(associationProperty = "length")
    void on(String event) {}
  }

  record Misrouted(@Association(aggregateType = String.class) ParcelId parcel)
      implements DomainEvent {}

  @AggregateRoot
  record Confused(@Identity ParcelId id) implements DomainEvent {}

  @io.github.gkosharovdev.sagaforge.core.annotation.Aggregate
  record Mixed(@io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier String id)
      implements DomainEvent {}

  static final class Holder {
    @io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver
    void save(Confused confused) {}

    @io.github.gkosharovdev.sagaforge.core.annotation.AggregateSaver
    void save(Mixed mixed) {}
  }

  private final ReflectiveAnnotationScanner scanner =
      new ReflectiveAnnotationScanner(DomainTypes.of(new JMoleculesDomainTypeResolver()));

  @Test
  @DisplayName("a jMolecules model with an outbox command validates")
  void validModel() {
    ValidationReport report =
        scanner.validateAll(List.of(ShippingSaga.class, Parcel.class, Notifier.class));

    assertThat(report.allErrors()).isEmpty();
  }

  @Test
  @DisplayName("R31: an identifier type two aggregates share, fixed by @Association(aggregateType)")
  void sharedIdentifierType() {
    ValidationReport report =
        scanner.validateAll(List.of(ShippingSaga.class, Parcel.class, Pallet.class));

    String reason =
        " concerns no known aggregate: identifier type ParcelId belongs to Pallet, Parcel. Name the"
            + " aggregate on the link member, or give the aggregate its own identifier type";
    assertThat(report.modelErrors())
        .containsExactlyInAnyOrder(
            "ParcelShipped (jMolecules DomainEvent)" + reason,
            "Ship (jMolecules @Command)" + reason)
        .noneMatch(error -> error.contains("ParcelLoaded"));
  }

  @Test
  @DisplayName("Property 16: identifier counts name jMolecules' members")
  void identifierCounts() {
    ValidationReport report =
        scanner.validateAll(List.of(TwiceSaga.class, Parcel.class, Unidentified.class));

    assertThat(report.allErrors())
        .contains(
            "Unidentified (jMolecules @AggregateRoot) must declare exactly one jMolecules @Identity"
                + " member, found 0",
            "ParcelTwice (jMolecules DomainEvent) must declare exactly one jMolecules link member"
                + " (Association, @Association or Identifier), found 2");
  }

  @Test
  @DisplayName("handler rules name jMolecules' @CommandHandler and the accepted command markers")
  void handlerRules() {
    ValidationReport report =
        scanner.validateAll(
            List.of(
                HandlingSaga.class,
                Parcel.class,
                NoCommandHandler.class,
                SecondShipper.class,
                Notifier.class,
                Crate.class));

    assertThat(report.allErrors())
        .contains(
            "@Saga class HandlingSaga must not declare jMolecules @CommandHandler method handle",
            "jMolecules @CommandHandler method handle in NoCommandHandler must declare exactly one"
                + " command parameter (@Command, or jMolecules @Command), found 0",
            "Command Ship has more than one command handler: Parcel.handle, SecondShipper.handle",
            "Command type Untargeted (jMolecules @Command) is handled by jMolecules @AggregateRoot"
                + " instance method Crate.handle and must declare a jMolecules link member"
                + " (Association, @Association or Identifier)");
  }

  @Test
  @DisplayName("an outbox command without a handler names command handlers in general")
  void outboxWithoutHandler() {
    assertThat(scanner.validateAll(List.of(Unhandled.class)).allErrors())
        .contains("Command Unhandled is delivered through the outbox but has no command handler");
  }

  @Test
  @DisplayName("loader and saver rules list the accepted aggregate markers")
  void loaderAndSaver() {
    assertThat(scanner.validateAll(List.of(Repository.class)).allErrors())
        .contains(
            "@AggregateLoader method load in Repository must take exactly one parameter and return"
                + " an aggregate type (@Aggregate, or jMolecules @AggregateRoot / AggregateRoot)",
            "@AggregateSaver method save in Repository must take exactly one aggregate-typed"
                + " parameter (@Aggregate, or jMolecules @AggregateRoot / AggregateRoot)");
  }

  @Test
  @DisplayName("a step parameter that is no event lists the accepted event markers")
  void stepParameter() {
    assertThat(scanner.validate(StringSaga.class).errors())
        .contains(
            "@SagaStep method on parameter type String must be a domain event (@DomainEvent, or"
                + " jMolecules @DomainEvent / DomainEvent)");
  }

  @Test
  @DisplayName("Property 17: an association naming a non-aggregate")
  void namedNonAggregate() {
    assertThat(scanner.validateAll(List.of(Misrouted.class, Parcel.class)).allErrors())
        .contains(
            "Domain event type Misrouted (jMolecules DomainEvent) names aggregate String, which is"
                + " not an aggregate (@Aggregate, or jMolecules @AggregateRoot / AggregateRoot)");
  }

  @Test
  @DisplayName("R30: two roles in jMolecules alone, and across vocabularies")
  void conflictingRoles() {
    assertThat(scanner.validateAll(List.of(Holder.class)).modelErrors())
        .contains(
            "Confused is marked jMolecules @AggregateRoot (aggregate) and jMolecules DomainEvent"
                + " (domain event); a type has one role",
            "Mixed is marked @Aggregate (aggregate) and jMolecules DomainEvent (domain event); a"
                + " type has one role");
  }
}
