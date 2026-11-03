package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestAggregate;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent2;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.ValidSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.AggregateInstanceHandlerCommandWithoutIdentifier;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.AggregateWithInstanceHandler;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.AggregateWithTwoIdentifiers;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.AggregateWithoutIdentifier;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.CommandWithTwoIdentifiers;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.CreateValidAggregate;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.DuplicateHandlerA;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.DuplicateHandlerB;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.DuplicateLoaderA;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.DuplicateLoaderB;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.DuplicateSaverA;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.DuplicateSaverB;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.EventWithNonAggregate;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.EventWithTwoIdentifiers;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.EventWithoutIdentifier;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.HandlerWithTwoCommandParams;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.HandlerWithoutCommandParam;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.MalformedLoader;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.MalformedSaver;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ReachedAggregateWithoutIdentifier;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ReachedCommandWithTwoIdentifiers;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ReachedEventWithoutIdentifier;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ReachingRepository;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ReachingSaga;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ReachingService;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.SharedCommand;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ValidAggregate;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ValidAggregateCommand;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ValidAggregateRepository;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ValidEvent;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ValidService;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.handlers.ValidServiceCommand;

/**
 * Handler, identifier, loader, saver, and model rules of {@code validateAll}
 * (specs/002-transactional-step-sequence/contracts/validation.md, spec FR-009 to FR-014).
 *
 * <p>Interpretation of the contract, which T024 implements:
 * <ul>
 *   <li>Messages are the literal text of the contract's "New error messages" table. In particular the
 *       aggregate/event identifier-count message has no "@Aggregate type"/"@DomainEvent type" prefix; it
 *       starts with the bare simple class name.</li>
 *   <li>Class-level errors (handler shape, identifier counts, event aggregate, loader/saver shape) land in the
 *       {@link SagaValidationResult} whose {@code sagaClass} is the offending class: the handler, loader or
 *       saver host class, or the {@code @Aggregate}/{@code @DomainEvent}/{@code @Command} type itself — also
 *       when that type is only reached from a signature. Rows marked "(model error)" land in
 *       {@link ValidationReport#modelErrors()}.</li>
 *   <li>A type reached from several signatures is validated once, so its errors are reported once.</li>
 *   <li>Lists of {@code {class}.{method}} in model errors are sorted in ascending lexicographic order of the
 *       full {@code "{class}.{method}"} string.</li>
 *   <li>In the instance-handler message, the first {@code {class}} is the command type and
 *       {@code {class}.{method}} is the aggregate class and its handler method.</li>
 *   <li>The loader/saver rule applies per method: each malformed method yields one message.</li>
 * </ul>
 * Each fixture set violates exactly one rule, so {@code allErrors()} is asserted to hold only the expected
 * messages.
 */
class ValidateAllTest {

    private final ReflectiveAnnotationScanner scanner = new ReflectiveAnnotationScanner();

    private ValidationReport validateAll(Class<?>... classes) {
        return scanner.validateAll(List.of(classes));
    }

    /** All errors in the class results for {@code type} (there should be at most one such result). */
    private static List<String> errorsOf(ValidationReport report, Class<?> type) {
        return report.classResults().stream()
                .filter(result -> result.sagaClass().equals(type))
                .flatMap(result -> result.errors().stream())
                .toList();
    }

    @Nested
    @DisplayName("fully valid model")
    class ValidModel {

        @Test
        @DisplayName("aggregate, repository, service, event, commands and a saga validate cleanly")
        void fullyValidModelIsValid() {
            ValidationReport report = validateAll(
                    ValidAggregate.class, ValidAggregateRepository.class, ValidService.class, ValidEvent.class,
                    ValidAggregateCommand.class, CreateValidAggregate.class, ValidServiceCommand.class,
                    ValidSaga.class, TestAggregate.class, TestEvent.class, TestEvent2.class);

            assertThat(report.allErrors()).isEmpty();
            assertThat(report.isValid()).isTrue();
        }

        @Test
        @DisplayName("the valid aggregate extends and implements no Saga Forge type")
        void validAggregateHasNoFrameworkSupertype() {
            assertThat(ValidAggregate.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(ValidAggregate.class.getInterfaces()).isEmpty();
        }
    }

    @Nested
    @DisplayName("handler rules")
    class HandlerRules {

        @Test
        @DisplayName("a handler with zero @Command parameters is rejected")
        void handlerWithoutCommandParam() {
            String expected = "@CommandHandler method handle in HandlerWithoutCommandParam"
                    + " must declare exactly one @Command parameter, found 0";

            ValidationReport report = validateAll(HandlerWithoutCommandParam.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, HandlerWithoutCommandParam.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("a handler with two @Command parameters is rejected")
        void handlerWithTwoCommandParams() {
            String expected = "@CommandHandler method handle in HandlerWithTwoCommandParams"
                    + " must declare exactly one @Command parameter, found 2";

            ValidationReport report = validateAll(HandlerWithTwoCommandParams.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, HandlerWithTwoCommandParams.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("two handlers for one command type are a model error")
        void duplicateHandlers() {
            // {class}.{method} entries sorted lexicographically.
            String expected = "Command SharedCommand has more than one @CommandHandler:"
                    + " DuplicateHandlerA.handle, DuplicateHandlerB.handle";

            ValidationReport report = validateAll(DuplicateHandlerB.class, DuplicateHandlerA.class, SharedCommand.class);

            assertThat(report.isValid()).isFalse();
            assertThat(report.modelErrors()).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }
    }

    @Nested
    @DisplayName("identifier rules")
    class IdentifierRules {

        @Test
        @DisplayName("an aggregate without @AggregateIdentifier is rejected")
        void aggregateWithoutIdentifier() {
            String expected = "AggregateWithoutIdentifier must declare exactly one @AggregateIdentifier field, found 0";

            ValidationReport report = validateAll(AggregateWithoutIdentifier.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, AggregateWithoutIdentifier.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("an aggregate with two @AggregateIdentifiers is rejected")
        void aggregateWithTwoIdentifiers() {
            String expected = "AggregateWithTwoIdentifiers must declare exactly one @AggregateIdentifier field, found 2";

            ValidationReport report = validateAll(AggregateWithTwoIdentifiers.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, AggregateWithTwoIdentifiers.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("an event without @AggregateIdentifier is rejected")
        void eventWithoutIdentifier() {
            String expected = "EventWithoutIdentifier must declare exactly one @AggregateIdentifier field, found 0";

            ValidationReport report = validateAll(EventWithoutIdentifier.class, ValidAggregate.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, EventWithoutIdentifier.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("an event with two @AggregateIdentifiers is rejected")
        void eventWithTwoIdentifiers() {
            String expected = "EventWithTwoIdentifiers must declare exactly one @AggregateIdentifier field, found 2";

            ValidationReport report = validateAll(EventWithTwoIdentifiers.class, ValidAggregate.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, EventWithTwoIdentifiers.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("a command with two @AggregateIdentifiers is rejected")
        void commandWithTwoIdentifiers() {
            String expected = "@Command type CommandWithTwoIdentifiers"
                    + " must declare at most one @AggregateIdentifier field, found 2";

            ValidationReport report = validateAll(CommandWithTwoIdentifiers.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, CommandWithTwoIdentifiers.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("a command handled by an aggregate instance method without identifier is a model error")
        void aggregateInstanceHandlerCommandWithoutIdentifier() {
            String expected = "@Command type AggregateInstanceHandlerCommandWithoutIdentifier"
                    + " is handled by @Aggregate instance method AggregateWithInstanceHandler.handle"
                    + " and must declare an @AggregateIdentifier field";

            ValidationReport report = validateAll(
                    AggregateWithInstanceHandler.class, AggregateInstanceHandlerCommandWithoutIdentifier.class);

            assertThat(report.isValid()).isFalse();
            assertThat(report.modelErrors()).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("a command without identifier is fine on its own")
        void commandWithoutIdentifierAloneIsValid() {
            ValidationReport report = validateAll(AggregateInstanceHandlerCommandWithoutIdentifier.class);

            assertThat(report.allErrors()).isEmpty();
        }

        @Test
        @DisplayName("an event whose aggregate is not an @Aggregate is rejected")
        void eventWithNonAggregate() {
            String expected = "@DomainEvent type EventWithNonAggregate names aggregate NotAnAggregate,"
                    + " which is not annotated @Aggregate";

            ValidationReport report = validateAll(EventWithNonAggregate.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, EventWithNonAggregate.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }
    }

    @Nested
    @DisplayName("loader and saver rules")
    class LoaderSaverRules {

        @Test
        @DisplayName("loaders with two parameters or a non-aggregate return type are rejected")
        void malformedLoader() {
            String twoParameters = "@AggregateLoader method loadWithTwoParameters in MalformedLoader"
                    + " must take exactly one parameter and return an @Aggregate type";
            String nonAggregate = "@AggregateLoader method loadNonAggregate in MalformedLoader"
                    + " must take exactly one parameter and return an @Aggregate type";

            ValidationReport report = validateAll(MalformedLoader.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, MalformedLoader.class)).containsExactlyInAnyOrder(twoParameters, nonAggregate);
            assertThat(report.allErrors()).containsExactlyInAnyOrder(twoParameters, nonAggregate);
        }

        @Test
        @DisplayName("savers with a non-aggregate parameter or two parameters are rejected")
        void malformedSaver() {
            String nonAggregate = "@AggregateSaver method saveNonAggregate in MalformedSaver"
                    + " must take exactly one @Aggregate-typed parameter";
            String twoParameters = "@AggregateSaver method saveWithTwoParameters in MalformedSaver"
                    + " must take exactly one @Aggregate-typed parameter";

            ValidationReport report = validateAll(MalformedSaver.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, MalformedSaver.class)).containsExactlyInAnyOrder(nonAggregate, twoParameters);
            assertThat(report.allErrors()).containsExactlyInAnyOrder(nonAggregate, twoParameters);
        }

        @Test
        @DisplayName("two loaders for one aggregate type are a model error")
        void duplicateLoaders() {
            // {class}.{method} entries sorted lexicographically.
            String expected = "Aggregate ValidAggregate has more than one @AggregateLoader:"
                    + " DuplicateLoaderA.load, DuplicateLoaderB.load";

            ValidationReport report = validateAll(DuplicateLoaderB.class, DuplicateLoaderA.class);

            assertThat(report.isValid()).isFalse();
            assertThat(report.modelErrors()).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("two savers for one aggregate type are a model error")
        void duplicateSavers() {
            // {class}.{method} entries sorted lexicographically.
            String expected = "Aggregate ValidAggregate has more than one @AggregateSaver:"
                    + " DuplicateSaverA.save, DuplicateSaverB.save";

            ValidationReport report = validateAll(DuplicateSaverB.class, DuplicateSaverA.class);

            assertThat(report.isValid()).isFalse();
            assertThat(report.modelErrors()).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }
    }

    @Nested
    @DisplayName("types reached from signatures")
    class ReachedTypes {

        @Test
        @DisplayName("a command reached from a handler signature is validated")
        void commandReachedFromHandler() {
            String expected = "@Command type ReachedCommandWithTwoIdentifiers"
                    + " must declare at most one @AggregateIdentifier field, found 2";

            ValidationReport report = validateAll(ReachingService.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, ReachedCommandWithTwoIdentifiers.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("an aggregate reached from loader and saver signatures is validated once")
        void aggregateReachedFromLoaderAndSaver() {
            String expected = "ReachedAggregateWithoutIdentifier"
                    + " must declare exactly one @AggregateIdentifier field, found 0";

            ValidationReport report = validateAll(ReachingRepository.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, ReachedAggregateWithoutIdentifier.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }

        @Test
        @DisplayName("an event reached from a saga step signature is validated")
        void eventReachedFromStep() {
            String expected = "ReachedEventWithoutIdentifier"
                    + " must declare exactly one @AggregateIdentifier field, found 0";

            ValidationReport report = validateAll(ReachingSaga.class, ValidAggregate.class);

            assertThat(report.isValid()).isFalse();
            assertThat(errorsOf(report, ReachedEventWithoutIdentifier.class)).containsExactly(expected);
            assertThat(report.allErrors()).containsExactly(expected);
        }
    }
}
