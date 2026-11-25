package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTrigger;
import io.github.gkosharovdev.sagaforge.core.compensation.CompensationTriggerKind;
import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.runtime.internal.InstanceLocks.Lease;
import io.github.gkosharovdev.sagaforge.runtime.internal.UnitOfWork.Collaborators;
import io.opentelemetry.api.common.Attributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The {@code CommandReceiver.receiveFailure} algorithm (specs/012-outbox-command-failure-feedback
 * contracts/command-receiver-failure.md, contracts/runtime-reaction.md): the reaction of the instance that sent a
 * failed outbox command. It orchestrates and decides nothing a work does not decide.
 *
 * <ol>
 *   <li>Find the instance's saga type; none → ignored, {@code missing}.</li>
 *   <li>Hold the instance lock (the one {@link EventRouter} uses) for the reaction and its compensations.</li>
 *   <li>Resolve the {@link Sender}: a step → {@link StartCompensation} with a {@code COMMAND_FAILED} trigger, then
 *       {@link CompensationRun}; a compensation → {@link RecordCompensationCommandFailure}; neither → ignored,
 *       {@code unknown-sender}.</li>
 *   <li>An ignored report still runs the set-aside callback, in a standalone {@link HandleCommand}, and logs
 *       {@code saga.command.failure.ignored}.</li>
 *   <li>After releasing the lock, run the {@code AFTER_COMMIT} outbox steps of every committed unit of work, in commit
 *       order.</li>
 * </ol>
 *
 * <p>The set-aside callback runs in exactly one transaction, which commits together with the reaction; a failed or
 * conflicted reaction rolls it back and throws, so the transport redelivers.</p>
 *
 * <p>Immutable; thread-safe if the host plug-ins are. Each call runs on the calling thread.</p>
 */
final class CommandFailureHandler {

    private final Map<String, SagaModel> sagasByType;
    private final Collaborators with;
    private final InstanceLocks instanceLocks;

    /**
     * @param sagas         the registered saga types
     * @param with          the collaborators every unit of work shares
     * @param instanceLocks the instance locks the event router holds, so a reaction and an event never interleave
     */
    CommandFailureHandler(List<SagaModel> sagas, Collaborators with, InstanceLocks instanceLocks) {
        this.sagasByType = sagas.stream().collect(Collectors.toUnmodifiableMap(SagaModel::typeName,
                Function.identity()));
        this.with = with;
        this.instanceLocks = instanceLocks;
    }

    /**
     * Reacts to {@code report}.
     *
     * @throws IllegalArgumentException if an argument is {@code null}
     * @throws IllegalStateException    if a unit of work is running on this thread
     * @throws RuntimeException         if the reaction could not be recorded (storage, conflict); nothing was set aside
     */
    void handle(CommandFailure report, Consumer<LocalTransaction> setAside) {
        if (report == null || setAside == null) {
            throw new IllegalArgumentException("report and setAside must not be null");
        }
        ReentrancyGuard.requireNotRunning();
        SagaInstrumentation telemetry = with.instrumentation();
        try (SagaInstrumentation.Traced span = telemetry.start(SagaInstrumentation.HANDLE)) {
            span.set(SagaInstrumentation.SAGA_ID, report.sagaId())
                    .set(SagaInstrumentation.COMMAND_TYPE, report.commandType())
                    .set(SagaInstrumentation.COMMAND_ID, report.commandId().value())
                    .set(SagaInstrumentation.COMPENSATION_TRIGGER, CompensationTriggerKind.COMMAND_FAILED.name())
                    .set(SagaInstrumentation.FAILURE_CLASSIFICATION, report.classification().name());
            try {
                Reaction reaction = react(report, setAside);
                span.set(SagaInstrumentation.SAGA_TYPE, reaction.sagaType())
                        .set(SagaInstrumentation.HANDLING_OUTCOME, reaction.outcome());
                countEnded(telemetry, span, reaction);
            } catch (RuntimeException | Error failure) {
                span.fail(failure);
                throw failure;
            }
        }
    }

    /** What a report led to, for telemetry: the saga type if known, and the outcome, {@code IGNORED} if ignored. */
    private record Reaction(String sagaType, String outcome) {
    }

    private static final String IGNORED = "IGNORED";

    /** Counts a reaction that ended the instance, as the event router counts a handling (010 research §4). */
    private static void countEnded(SagaInstrumentation telemetry, SagaInstrumentation.Traced span,
            Reaction reaction) {
        String ended = HandlingOutcome.COMPENSATED.name().equals(reaction.outcome()) ? "compensated"
                : HandlingOutcome.COMPENSATION_FAILED.name().equals(reaction.outcome()) ? "compensation_failed"
                : null;
        if (ended != null) {
            telemetry.count(SagaInstrumentation.SAGA_ENDED_COUNTER, Attributes.of(SagaInstrumentation.SAGA_TYPE,
                    reaction.sagaType(), SagaInstrumentation.SAGA_OUTCOME, ended));
            span.event(SagaInstrumentation.SAGA_ENDED_COUNTER, Attributes.of(SagaInstrumentation.SAGA_OUTCOME,
                    ended));
        }
    }

    private Reaction react(CommandFailure report, Consumer<LocalTransaction> setAside) {
        Optional<SagaModel> found = with.settings().storage().findById(report.sagaId())
                .map(SagaInstance::sagaType).map(sagasByType::get);
        if (found.isEmpty()) {
            Correlation about = new Correlation(null, null, report.sagaId(), null);
            return new Reaction(null, ignore(about, report, "missing", setAside));
        }
        SagaModel saga = found.get();
        Correlation about = new Correlation(null, saga.typeName(), report.sagaId(), null);
        List<Supplier<List<Throwable>>> afterRelease = new ArrayList<>();
        try {
            String outcome;
            try (Lease lease = instanceLocks.acquire(report.sagaId())) {
                outcome = switch (Sender.resolve(saga, report.senderName())) {
                    case Sender.StepSender step -> compensate(saga, about, report, setAside, afterRelease);
                    case Sender.CompensationSender compensation -> failCompensation(saga, about,
                            compensation.forStep(), report, setAside, afterRelease);
                    case Sender.Unknown unknown -> ignore(about, report, "unknown-sender", setAside);
                };
            }
            return new Reaction(saga.typeName(), outcome);
        } finally {
            afterRelease.forEach(Supplier::get);
        }
    }

    /** §A: a step sent the command; compensate every completed step, the sender included. */
    private String compensate(SagaModel saga, Correlation about, CommandFailure report,
            Consumer<LocalTransaction> setAside, List<Supplier<List<Throwable>>> afterRelease) {
        CompensationTrigger trigger = CompensationTrigger.commandFailed(report, with.settings().sources().now());
        UnitOfWork unitOfWork = UnitOfWork.forInstance(with, saga, report.sagaId(), about);
        switch (unitOfWork.run(StartCompensation.afterCommandFailure(trigger, setAside))) {
            case UnitOfWork.Skipped<CompensationTrigger, StartCompensation.NotStarted> skipped -> {
                return ignore(about, report, reasonOf(skipped.reason(), report.sagaId()), setAside);
            }
            case UnitOfWork.Committed<CompensationTrigger, StartCompensation.NotStarted> committed -> {
                with.diagnostics().compensationStarted(unitOfWork.correlation(), committed.value());
                afterRelease.add(committed::release);
                with.instrumentation().event("saga_forge.compensation.started", Attributes.empty());
                return new CompensationRun(with).run(saga, report.sagaId(), about, afterRelease,
                        committed.unitOfWorkId()).outcome().name();
            }
            case UnitOfWork.Failed<CompensationTrigger, StartCompensation.NotStarted> failed ->
                    throw UnitOfWork.propagate(failed.cause());
            case UnitOfWork.Conflicted<CompensationTrigger, StartCompensation.NotStarted> conflicted ->
                    throw conflicted.conflict();
        }
    }

    /** §B: a compensation sent the command; its step's compensation failed after all. */
    private String failCompensation(SagaModel saga, Correlation about, String forStep,
            CommandFailure report, Consumer<LocalTransaction> setAside,
            List<Supplier<List<Throwable>>> afterRelease) {
        UnitOfWork unitOfWork = UnitOfWork.forInstance(with, saga, report.sagaId(), about);
        switch (unitOfWork.run(new RecordCompensationCommandFailure(forStep, report, setAside))) {
            case UnitOfWork.Skipped<SagaFailure, String> skipped -> {
                return ignore(about, report, skipped.reason(), setAside);
            }
            case UnitOfWork.Committed<SagaFailure, String> committed -> {
                with.diagnostics().compensationFailed(unitOfWork.correlation(), committed.value());
                afterRelease.add(committed::release);
                return HandlingOutcome.COMPENSATION_FAILED.name();
            }
            case UnitOfWork.Failed<SagaFailure, String> failed -> throw UnitOfWork.propagate(failed.cause());
            case UnitOfWork.Conflicted<SagaFailure, String> conflicted -> throw conflicted.conflict();
        }
    }

    /** The instance cannot react: only set the message aside, in a transaction of its own, and say why. */
    private String ignore(Correlation about, CommandFailure report, String reason,
            Consumer<LocalTransaction> setAside) {
        EngineSettings settings = with.settings();
        HandleCommand.run(settings, with.diagnostics(), () -> setAside.accept(settings.transactions().current()
                .orElseThrow(() -> new IllegalStateException("no transaction is active to set "
                        + report.commandId() + " aside"))));
        with.diagnostics().commandFailureIgnored(about, report, reason);
        return IGNORED;
    }

    private String reasonOf(StartCompensation.NotStarted notStarted, String sagaId) {
        return switch (notStarted) {
            case ALREADY_COMPENSATING -> "compensating";
            case NOTHING_COMMITTED -> "nothing-committed";
            case MISSING_OR_TERMINAL -> with.settings().storage().findById(sagaId)
                    .map(instance -> "terminal:" + instance.status()).orElse("missing");
        };
    }
}
