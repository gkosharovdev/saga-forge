package io.github.gkosharovdev.sagaforge.engine.internal;

import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.lifecycle.DuplicateSagaInstanceException;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics.Correlation;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSagaLifecycle.PendingWrite;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaModel.Step;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Collaborators;
import io.github.gkosharovdev.sagaforge.engine.internal.SagaUnitOfWork.Trigger;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code EventReceiver.receive} algorithm (contracts/event-receiver.md, FR-001 to FR-006).
 *
 * <ol>
 *   <li><b>Validate first</b> (FR-006): a {@code null} or non-{@code @DomainEvent} event, or a missing or empty
 *       association value for any reacting step of any saga type, is an {@link IllegalArgumentException} before
 *       anything runs.</li>
 *   <li><b>Ignored</b>: no registered saga type has a step for the event's exact class → a report with no
 *       results ({@code saga.event.ignored}).</li>
 *   <li><b>Per saga type, in registration order</b>:
 *     <ul>
 *       <li>start step: {@code EngineSagaLifecycle.start} (an active instance with the value →
 *           {@code DUPLICATE_START}), the creation write in its own transaction ({@code CREATED} instance and
 *           entry, FR-012, FR-024), then the first unit of work ({@code started = true});</li>
 *       <li>other step: {@code findByAssociation} filtered to the saga type; one unit of work per active instance
 *           in the order returned, each committed or rolled back on its own; none → {@code UNMATCHED}; only
 *           terminal ones → one {@code UNEXPECTED} result for the most recently returned (FR-002 to FR-004).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Transitional (US1): an exception from storage, the creation write or a unit of work propagates from
 * {@code receive} and stops the fan-out; T053 adds the FR-005 error path, T072 the duplicate-start and instance
 * locks, T086 the reentrancy check.</p>
 *
 * <p>Immutable; thread-safe if the host plug-ins are. Each call runs on the calling thread.</p>
 */
final class EventRouter {

    /** One saga type reacting to the event, through its step for the event's class. */
    private record Reaction(SagaModel saga, Step step, Object event, String associationValue) {

        Correlation correlation() {
            return Correlation.of(event).forSaga(saga.typeName());
        }
    }

    private final List<SagaModel> sagas;
    private final Collaborators with;
    private final EngineSettings settings;

    /**
     * @param sagas the registered saga types, in registration order
     * @param with  the collaborators every unit of work shares
     */
    EventRouter(List<SagaModel> sagas, Collaborators with) {
        this.sagas = List.copyOf(sagas);
        this.with = with;
        this.settings = with.settings();
    }

    HandlingReport receive(Object event) {
        requireDomainEvent(event);
        List<Reaction> reactions = reactionsTo(event);
        if (reactions.isEmpty()) {
            with.diagnostics().eventIgnored(event);
            return new HandlingReport(event, List.of());
        }
        List<SagaHandlingResult> results = new ArrayList<>();
        reactions.forEach(reaction -> results.addAll(handle(reaction)));
        return new HandlingReport(event, results);
    }

    private List<Reaction> reactionsTo(Object event) {
        List<Reaction> reactions = new ArrayList<>();
        for (SagaModel saga : sagas) {
            saga.stepFor(event.getClass()).ifPresent(step -> reactions.add(
                    new Reaction(saga, step, event, requireAssociationValue(saga, step, event))));
        }
        return reactions;
    }

    private List<SagaHandlingResult> handle(Reaction reaction) {
        return reaction.step().start() ? List.of(start(reaction)) : resume(reaction);
    }

    private SagaHandlingResult start(Reaction reaction) {
        EngineSagaLifecycle lifecycle = new EngineSagaLifecycle(reaction.saga(), settings.storage(),
                settings.sources());
        SagaInstance created;
        try {
            created = lifecycle.start(reaction.saga().type(), reaction.step().associationProperty(),
                    reaction.associationValue());
        } catch (DuplicateSagaInstanceException duplicate) {
            return duplicateStart(reaction);
        }
        commitCreation(lifecycle.drain(created.sagaId()));
        return runUnitOfWork(reaction, created.sagaId(), true);
    }

    private void commitCreation(PendingWrite creation) {
        LocalTransaction transaction = settings.transactions().begin();
        try {
            settings.storage().save(creation.instance(), creation.entries());
            transaction.commit();
        } catch (RuntimeException | Error failure) {
            LocalTransactions.rollBackAfter(settings.transactions(), transaction, failure);
            throw failure;
        }
    }

    private List<SagaHandlingResult> resume(Reaction reaction) {
        List<SagaInstance> correlated = settings.storage()
                .findByAssociation(reaction.step().associationProperty(), reaction.associationValue()).stream()
                .filter(instance -> instance.sagaType().equals(reaction.saga().typeName()))
                .toList();
        List<SagaInstance> active = correlated.stream()
                .filter(instance -> !EngineSagaLifecycle.isTerminal(instance.status()))
                .toList();
        if (!active.isEmpty()) {
            List<SagaHandlingResult> results = new ArrayList<>();
            active.forEach(instance -> results.add(runUnitOfWork(reaction, instance.sagaId(), false)));
            return results;
        }
        if (!correlated.isEmpty()) {
            return List.of(unexpected(reaction, correlated.get(correlated.size() - 1).sagaId()));
        }
        return List.of(unmatched(reaction));
    }

    private SagaHandlingResult runUnitOfWork(Reaction reaction, String sagaId, boolean started) {
        Trigger trigger = new Trigger(reaction.saga(), reaction.step(), reaction.event(), sagaId, started);
        return new SagaUnitOfWork(trigger, with).run();
    }

    private SagaHandlingResult duplicateStart(Reaction reaction) {
        with.diagnostics().eventDuplicateStart(reaction.correlation(), reaction.step().associationProperty(),
                reaction.associationValue());
        return noUnitOfWork(reaction, HandlingOutcome.DUPLICATE_START, null);
    }

    private SagaHandlingResult unexpected(Reaction reaction, String sagaId) {
        with.diagnostics().eventUnexpected(reaction.correlation().instance(sagaId), reaction.step().name());
        return noUnitOfWork(reaction, HandlingOutcome.UNEXPECTED, sagaId);
    }

    private SagaHandlingResult unmatched(Reaction reaction) {
        with.diagnostics().eventUnmatched(reaction.correlation(), reaction.step().name());
        return noUnitOfWork(reaction, HandlingOutcome.UNMATCHED, null);
    }

    private static SagaHandlingResult noUnitOfWork(Reaction reaction, HandlingOutcome outcome, String sagaId) {
        return new SagaHandlingResult(reaction.saga().typeName(), sagaId, outcome, false, null, null, null,
                List.of());
    }

    private static void requireDomainEvent(Object event) {
        if (event == null) {
            throw new IllegalArgumentException("domainEvent must not be null");
        }
        if (!event.getClass().isAnnotationPresent(DomainEvent.class)) {
            throw new IllegalArgumentException(event.getClass().getName() + " is not annotated @DomainEvent");
        }
    }

    private static String requireAssociationValue(SagaModel saga, Step step, Object event) {
        return saga.associationValue(step, event).orElseThrow(() -> new IllegalArgumentException(
                "Event " + event.getClass().getName() + " has no value for association property '"
                        + step.associationProperty() + "' of step " + step.name() + " of saga "
                        + saga.typeName()));
    }
}
