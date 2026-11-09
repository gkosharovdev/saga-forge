package io.github.gkosharovdev.sagaforge.engine.support;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.execution.harness.SagaTestHarness;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextRecord;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import io.github.gkosharovdev.sagaforge.engine.SagaEngine;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs one event sequence on a 002 {@link SagaTestHarness} and on a {@link SagaEngine}, each over its own fresh
 * fixture objects, with the same fixed {@link Clock} and the same deterministic saga-id sequence, and exposes what
 * each side ended with for comparison (specs/004-saga-engine tasks.md T046, SC-001, SC-009).
 *
 * <p>The engine side runs over {@link InMemoryTransactionBoundary}, {@link TransactionalInMemoryStorageProvider},
 * an {@link InMemorySagaContextStore} and a {@link RecordingEventDispatcher} behind the default
 * {@code DispatchingEventHandoff}; every {@link Snapshottable} fixture object is a participant of the boundary, as it
 * is snapshotted by the harness. Both sides receive the same event objects, in order; the harness through
 * {@code deliverAll}, the engine through {@code receive}. An exception from {@code receive} is kept in
 * {@link Side#failures()} and does not stop the sequence.</p>
 *
 * <p>{@link #restart()} models a process restart between events: the harness is rebuilt from its
 * {@code persistedState()} and the engine is rebuilt over the same stores; both keep the same client objects (the
 * client's database survives) and nothing else.</p>
 *
 * <p><strong>Test-only scaffolding.</strong> Not thread-safe; one run per test.</p>
 */
public final class DifferentialRun {

    /** The fixed instant both sides read. */
    public static final Clock CLOCK = Clock.fixed(SagaTestHarness.DEFAULT_INSTANT, ZoneOffset.UTC);

    /**
     * The client objects of one side: handler hosts, aggregate classes and loader/saver hosts. A fresh
     * {@code Fixtures} is created per side, so the two sides share no state.
     */
    public interface Fixtures {

        /** Service-hosted {@code @CommandHandler} instances. */
        List<Object> handlers();

        /** {@code @Aggregate} classes whose {@code @CommandHandler}s become routes. */
        default List<Class<?>> aggregates() {
            return List.of();
        }

        /** Objects hosting {@code @AggregateLoader}/{@code @AggregateSaver} methods. */
        default List<Object> repositories() {
            return List.of();
        }

        /** The client state to compare after the run, as a value with a meaningful {@code equals}. */
        Object state();
    }

    /**
     * What one side ended with for one instance, without {@code SagaInstance.state}, which the engine always leaves
     * empty (G-ENG-06), and without what only the engine records since {@code specs/005-step-retry}: a step's
     * attempts and handled command identities, the failure's classification and attempt count, and a pending retry
     * (the 002 harness is the reference for the 004 semantics only).
     */
    public record InstanceView(String sagaId, SagaStatus status, Map<String, String> associations,
            Map<String, StepRecord> steps, String outcome, SagaFailure failure) {

        static InstanceView of(SagaInstance instance) {
            Map<String, StepRecord> steps = new java.util.HashMap<>();
            instance.steps().forEach((name, step) -> steps.put(name,
                    new StepRecord(step.status(), step.touchedAggregates())));
            SagaFailure failure = instance.failure() == null ? null
                    : new SagaFailure(instance.failure().stepName(), instance.failure().causeType(),
                            instance.failure().message(), instance.failure().failedAt());
            return new InstanceView(instance.sagaId(), instance.status(), instance.associations(),
                    Map.copyOf(steps), instance.outcome(), failure);
        }
    }

    /**
     * What one side ended with.
     *
     * @param instances    every stored instance of the saga type, in creation order
     * @param contexts     the stored {@code SagaContext} values per saga id (empty map if none)
     * @param dispatched   every event handed to {@code EventDispatcher.dispatch}, in order
     * @param fixtureState {@link Fixtures#state()} after the run
     * @param failures     exceptions thrown out of {@code receive} (always empty for the harness)
     */
    public record Side(List<InstanceView> instances, Map<String, Map<String, Object>> contexts,
            List<Object> dispatched, Object fixtureState, List<Throwable> failures) {

        /** Everything but {@link #failures()}, for an equality check between the sides. */
        public List<Object> comparable() {
            return List.of(instances, contexts, dispatched, fixtureState);
        }
    }

    private final Class<?> sagaType;
    private final Fixtures harnessFixtures;
    private final Fixtures engineFixtures;
    private final List<Object> harnessDispatchedBeforeRestart = new ArrayList<>();
    private SagaTestHarness harness;
    private SagaEngine engine;
    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
    private final InMemorySagaContextStore contextStore = new InMemorySagaContextStore();
    private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
    private final List<Throwable> engineFailures = new ArrayList<>();

    private DifferentialRun(Class<?> sagaType, Supplier<Fixtures> fixtures) {
        this.sagaType = sagaType;
        this.harnessFixtures = fixtures.get();
        this.engineFixtures = fixtures.get();
        clientObjects(engineFixtures).stream()
                .filter(Snapshottable.class::isInstance)
                .map(Snapshottable.class::cast)
                .forEach(boundary::participant);
        this.harness = buildHarness(SagaTestHarness.builder());
        this.engine = buildEngine();
    }

    /**
     * A harness and a started engine for {@code sagaType}, each over its own {@code fixtures.get()}.
     *
     * @param sagaType the {@code @Saga} class
     * @param fixtures creates the client objects of one side; called once per side
     */
    public static DifferentialRun of(Class<?> sagaType, Supplier<Fixtures> fixtures) {
        return new DifferentialRun(sagaType, fixtures);
    }

    /** Delivers every event, in order, to both sides. */
    public DifferentialRun run(Object... events) {
        for (Object event : events) {
            harness.deliverAll(event);
            try {
                engine.receive(event);
            } catch (RuntimeException | Error failure) {
                engineFailures.add(failure);
            }
        }
        return this;
    }

    /**
     * Restarts both sides: a new harness from the current harness's persisted state, and a new started engine over
     * the same stores and dispatcher. Each side keeps its client objects; each gets a fresh saga-id sequence.
     */
    public DifferentialRun restart() {
        harnessDispatchedBeforeRestart.addAll(harness.dispatcher().dispatched());
        harness = buildHarness(SagaTestHarness.fromPersistedState(harness.persistedState()));
        engine = buildEngine();
        return this;
    }

    /** What the harness ended with. */
    public Side harness() {
        List<SagaInstance> instances = harness.instances(sagaType);
        Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
        instances.forEach(instance -> contexts.put(instance.sagaId(), Map.copyOf(harness.context(instance.sagaId()))));
        List<Object> dispatched = new ArrayList<>(harnessDispatchedBeforeRestart);
        dispatched.addAll(harness.dispatcher().dispatched());
        return new Side(views(instances), contexts, List.copyOf(dispatched), harnessFixtures.state(), List.of());
    }

    /** What the engine ended with (committed state only). */
    public Side engine() {
        List<SagaInstance> instances = storage.committed().all().stream()
                .filter(instance -> instance.sagaType().equals(sagaType.getName()))
                .toList();
        Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
        instances.forEach(instance -> contexts.put(instance.sagaId(), contextStore.find(instance.sagaId())
                .map(SagaContextRecord::values).orElse(Map.of())));
        return new Side(views(instances), contexts, dispatcher.dispatched(), engineFixtures.state(),
                List.copyOf(engineFailures));
    }

    /** The harness under test. */
    public SagaTestHarness harnessUnderTest() {
        return harness;
    }

    /** The engine under test. */
    public SagaEngine engineUnderTest() {
        return engine;
    }

    /** The engine side's storage. */
    public TransactionalInMemoryStorageProvider engineStorage() {
        return storage;
    }

    /** The engine side's fixtures. */
    public Fixtures engineFixtures() {
        return engineFixtures;
    }

    /** The harness side's fixtures. */
    public Fixtures harnessFixtures() {
        return harnessFixtures;
    }

    private SagaTestHarness buildHarness(SagaTestHarness.Builder builder) {
        builder.saga(sagaType)
                .clock(CLOCK)
                .sagaIds(sagaIdSequence());
        harnessFixtures.handlers().forEach(builder::handler);
        harnessFixtures.aggregates().forEach(builder::aggregate);
        harnessFixtures.repositories().forEach(builder::repository);
        return builder.build();
    }

    private SagaEngine buildEngine() {
        MapHandlerInstanceProvider instances = new MapHandlerInstanceProvider();
        Set<Object> clientObjects = clientObjects(engineFixtures);
        clientObjects.forEach(instances::register);
        SagaEngine.Builder builder = SagaEngine.builder()
                .register(sagaType)
                .storage(storage)
                .contextStore(contextStore)
                .transactions(boundary)
                .dispatcher(dispatcher)
                .handlerInstances(instances)
                .clock(CLOCK)
                .sagaIds(sagaIdSequence());
        clientObjects.forEach(object -> builder.register(object.getClass()));
        engineFixtures.aggregates().forEach(builder::register);
        SagaEngine built = builder.build();
        built.start();
        return built;
    }

    private static Set<Object> clientObjects(Fixtures fixtures) {
        Set<Object> clientObjects = new LinkedHashSet<>(fixtures.handlers());
        clientObjects.addAll(fixtures.repositories());
        return clientObjects;
    }

    private static List<InstanceView> views(List<SagaInstance> instances) {
        return instances.stream().map(InstanceView::of).toList();
    }

    /** {@code saga-1}, {@code saga-2}, ... — one independent sequence per side. */
    private static Supplier<String> sagaIdSequence() {
        AtomicInteger next = new AtomicInteger();
        return () -> "saga-" + next.incrementAndGet();
    }
}
