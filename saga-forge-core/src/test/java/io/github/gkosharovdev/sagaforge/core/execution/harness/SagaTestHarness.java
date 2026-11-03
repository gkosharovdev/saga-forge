package io.github.gkosharovdev.sagaforge.core.execution.harness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.DomainEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.Snapshottable;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessEventLog.Kind;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessSagaModel.Step;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessUnitOfWork.Collaborators;
import io.github.gkosharovdev.sagaforge.core.execution.harness.HarnessUnitOfWork.Participant;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult.Outcome;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;

/**
 * Executable reference model of the saga-scoped unit of work (contracts/saga-unit-of-work.md), standing in
 * for the not-yet-built saga engine in tests.
 *
 * <p><strong>Test-only scaffolding.</strong> Lives in {@code src/test}; never move to {@code src/main}.</p>
 *
 * <h2>Building</h2>
 * <pre>{@code
 * SagaTestHarness harness = SagaTestHarness.builder()
 *         .saga(ThreeStepSaga.class)                 // validated with ReflectiveAnnotationScanner.validate
 *         .handler(new ReservationService())         // service-hosted @CommandHandler methods, routed by
 *         .handler(new InvoiceService())             //   their single @Command parameter's exact class
 *         .handler(new ShipmentService())
 *         .clock(fixedClock)                         // optional; default: fixed at 2026-01-01T00:00:00Z UTC
 *         .build();
 * }</pre>
 * <h2>Aggregate-hosted handlers and persistence (contracts/command-gateway.md)</h2>
 * <pre>{@code
 * SagaTestHarness harness = SagaTestHarness.builder()
 *         .saga(CreateBookingSaga.class)
 *         .handler(new PaymentService())             // service-hosted
 *         .aggregate(Inventory.class)                // instance-method @CommandHandlers ...
 *         .repository(inventoryRepository)           //   ... loaded / saved by its @AggregateLoader / @AggregateSaver
 *         .aggregate(Booking.class)                  // creation (constructor) @CommandHandler ...
 *         .repository(bookingRepository)             //   ... the new aggregate saved by its @AggregateSaver
 *         .build();
 * }</pre>
 * <p>{@link Builder#aggregate(Class)} registers every {@code @CommandHandler} of an {@code @Aggregate} class:
 * instance methods (target loaded by the command's {@code @AggregateIdentifier}), constructors and static factories
 * (creation). {@link Builder#repository(Object)} registers a client object whose {@code @AggregateLoader} /
 * {@code @AggregateSaver} methods take precedence over the {@link #resolver() resolver}; it is rolled back with each
 * unit of work if it is {@link Snapshottable}. The resolver is an {@link InMemoryAggregateResolver} over
 * {@link #aggregates()} supporting exactly the types passed to {@link Builder#resolvedAggregate(Class)} (none by
 * default), or the one given to {@link Builder#resolver(AggregateResolver)}. Loading: client loader, else a
 * supporting resolver; absent → {@code AggregateNotFoundException}. Saving: client saver, else a supporting
 * resolver, else nothing.</p>
 * <p><b>Startup check</b>: {@link Builder#build()} rejects an aggregate with instance-method handlers and neither a
 * client loader nor a supporting resolver (spec FR-013; the scanner cannot see host resolvers, research §3).</p>
 *
 * <p>Optional builder inputs: {@link Builder#participant(Snapshottable)} (extra state rolled back with each
 * unit of work), {@link Builder#sagaIds(Supplier)} (saga id generator; default random UUIDs), and
 * pre-populated {@link Builder#storage(InMemoryStorageProvider) storage},
 * {@link Builder#contextStore(InMemorySagaContextStore) context store} and
 * {@link Builder#aggregateStore(InMemoryAggregateStore) aggregate store} (to resume from persisted state).
 * Handler instances that implement {@link Snapshottable} are snapshotted at the start of every unit of work and
 * restored on rollback, as are the storage provider and the aggregate store; the context store never is.</p>
 *
 * <h2>Delivering events</h2>
 * <p>{@link #deliver(Object)} models an event arriving through the transport. For each registered saga type
 * whose step reacts to the event's exact class:</p>
 * <ul>
 *   <li>{@code @StartSaga} step: a unit of work starts a new instance, correlated on the step's
 *       {@code associationProperty} value read from the event (record accessor, getter, or field).</li>
 *   <li>other step: the instance is resolved through {@code StorageProvider.findByAssociation}, filtered to the
 *       saga type. An active instance runs a unit of work; otherwise a terminal instance makes the event
 *       {@link Outcome#UNEXPECTED}, and no instance makes it {@link Outcome#UNMATCHED}. Neither has an
 *       effect beyond the {@link #eventLog() event log} (FR-003).</li>
 * </ul>
 * <p>An event no registered saga reacts to is {@link Outcome#IGNORED}. {@code deliver} returns the single
 * {@link UnitOfWorkResult}; {@link #deliverAll(Object)} returns one per affected saga type / instance. A failing
 * unit of work is never rethrown: it is reported through the result ({@link UnitOfWorkResult#cause()},
 * {@link UnitOfWorkResult#failure()}) and the instance is left {@code FAILED}. See {@link HarnessUnitOfWork} for
 * the exact begin / step / commit / rollback sequence, and {@link HarnessCommandGateway} for command routing.</p>
 *
 * <p>Events dispatched after commit go to {@link #dispatcher()} only; the harness does not loop them back unless
 * built with {@link Builder#redeliverDispatched()}, which delivers each after-commit dispatch back into the harness
 * once the dispatching unit of work has finished (depth-first; sagas that keep dispatching each other's start
 * events would recurse). {@code deliver} must not be called while a unit of work runs (it may be called from a
 * dispatcher listener, which runs after commit).</p>
 *
 * <h2>Publishing</h2>
 * <p>{@link #eventPublisher()} is the harness' {@link io.github.gkosharovdev.sagaforge.core.event.EventPublisher}
 * ({@link HarnessEventPublisher}): inside a unit of work it is {@code DomainEvents.record}; outside one it
 * dispatches the event through {@link #dispatcher()} immediately and then delivers that event (only that event,
 * once) through {@link #deliverAll(Object)}, so publishing a start event starts a saga.</p>
 *
 * <h2>Inspecting</h2>
 * <p>{@link #instance(String)}, {@link #findInstance(String)}, {@link #instances(Class)},
 * {@link #context(String)}, {@link #eventLog()}, plus the collaborators {@link #storage()},
 * {@link #contextStore()}, {@link #aggregates()}, {@link #dispatcher()}, {@link #lifecycle()},
 * {@link #handlers()}, {@link #commandGateway()}, {@link #resolver()}, {@link #eventPublisher()} and
 * {@link #clock()}.</p>
 *
 * <h2>Resuming in a fresh harness (FR-020, SC-003)</h2>
 * <pre>{@code
 * PersistedState state = harness.persistedState();           // instances, context records, aggregate store
 * SagaTestHarness resumed = SagaTestHarness.fromPersistedState(state)
 *         .saga(CreateBookingSaga.class)                      // re-register everything ...
 *         .handler(paymentService)                            // ... client objects holding their own state
 *         .aggregate(Inventory.class).repository(inventoryRepository)   // are passed again (same or copies)
 *         .aggregate(Booking.class).repository(bookingRepository)
 *         .build();
 * resumed.deliver(new PaymentConfirmedEvent(paymentId, bookingId));
 * }</pre>
 * <p>The fresh harness starts from copies of the stores and shares no store memory with this one; see
 * {@link PersistedState} for what is and is not carried.</p>
 *
 * <h2>One unit of work per instance at a time (FR-022)</h2>
 * <p>Not thread-safe: one unit of work at a time, on the caller's thread. {@code deliver}, {@code deliverAll}
 * and {@link #persistedState()} throw {@link IllegalStateException} while a unit of work runs, and every unit of
 * work of an existing instance is additionally guarded by its saga id. The instance a unit of work starts from
 * is re-read from storage immediately before it opens. Enforcement across processes (for example an optimistic
 * version on {@code SagaInstance}) is deferred to the engine and storage features (research §11).</p>
 */
public final class SagaTestHarness {

    /** Default clock instant. */
    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private final Map<Class<?>, HarnessSagaModel> sagas;
    private final InMemoryStorageProvider storage;
    private final InMemorySagaContextStore contextStore;
    private final InMemoryAggregateStore aggregates;
    private final RecordingEventDispatcher dispatcher = new RecordingEventDispatcher();
    private final MapHandlerInstanceProvider handlers = new MapHandlerInstanceProvider();
    private final HarnessEventLog eventLog = new HarnessEventLog();
    private final HarnessSagaLifecycle lifecycle;
    private final HarnessCommandGateway gateway;
    private final AggregateResolver resolver;
    private final HarnessEventPublisher eventPublisher;
    private final Clock clock;
    private final Collaborators collaborators;
    private HarnessUnitOfWork active;
    /** Saga ids of existing instances whose unit of work is running (FR-022; at most one, single-threaded). */
    private final Set<String> running = new HashSet<>();
    private Object publishing;

    private SagaTestHarness(Builder builder) {
        this.clock = builder.clock;
        this.storage = builder.storage;
        this.contextStore = builder.contextStore;
        this.aggregates = builder.aggregates;
        this.lifecycle = new HarnessSagaLifecycle(storage, clock, builder.sagaIds);
        this.gateway = new HarnessCommandGateway(() -> active);
        Map<Class<?>, HarnessSagaModel> models = new LinkedHashMap<>();
        for (Class<?> sagaType : builder.sagaTypes) {
            if (models.putIfAbsent(sagaType, HarnessSagaModel.of(sagaType)) != null) {
                throw new IllegalArgumentException("Saga " + sagaType.getName() + " registered twice");
            }
            dispatcher.registerSagaType(sagaType);
        }
        this.sagas = models;
        List<Participant> participants = new ArrayList<>();
        participants.add(new Participant(storage::snapshot, storage::restore));
        participants.add(new Participant(aggregates::snapshot, aggregates::restore));
        for (Object handler : builder.handlers) {
            handlers.register(handler);
            gateway.registerServiceHandlers(handler.getClass(), handlers);
            if (handler instanceof Snapshottable snapshottable) {
                participants.add(new Participant(snapshottable::snapshot, snapshottable::restore));
            }
        }
        if (builder.resolver != null && !builder.resolvedAggregates.isEmpty()) {
            throw new IllegalArgumentException("resolvedAggregate(...) applies to the default in-memory resolver"
                    + " only; do not combine it with resolver(...)");
        }
        this.resolver = builder.resolver != null
                ? builder.resolver
                : new InMemoryAggregateResolver(aggregates, builder.resolvedAggregates);
        HarnessAggregateAccess access = HarnessAggregateAccess.of(builder.repositories, resolver);
        for (Class<?> aggregateType : builder.aggregateTypes) {
            gateway.registerAggregateHandlers(aggregateType, access);
        }
        Set<Object> snapshotted = Collections.newSetFromMap(new IdentityHashMap<>());
        builder.handlers.stream().filter(Snapshottable.class::isInstance).forEach(snapshotted::add);
        for (Object repository : builder.repositories) {
            if (repository instanceof Snapshottable snapshottable && snapshotted.add(repository)) {
                participants.add(new Participant(snapshottable::snapshot, snapshottable::restore));
            }
        }
        for (Snapshottable participant : builder.participants) {
            participants.add(new Participant(participant::snapshot, participant::restore));
        }
        this.eventPublisher = new HarnessEventPublisher(this::publishImmediately);
        if (builder.redeliverDispatched) {
            dispatcher.onDispatch(this::redeliver);
        }
        this.collaborators = new Collaborators(lifecycle, storage, contextStore, dispatcher, gateway, eventLog,
                clock, List.copyOf(participants));
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A new builder pre-populated with fresh stores built from {@code state}: {@link PersistedState#newStorage()},
     * {@link PersistedState#newContextStore()} and {@link PersistedState#newAggregateStore()}. Nothing else is
     * pre-populated: register the sagas, handlers, aggregates and repositories again, including the client
     * objects that hold aggregate state of their own (see {@link PersistedState}). The resulting harness shares
     * no store memory with the harness {@code state} was captured from (FR-020, SC-003).
     *
     * @throws IllegalArgumentException if {@code state} is {@code null}
     */
    public static Builder fromPersistedState(PersistedState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        return builder()
                .storage(state.newStorage())
                .contextStore(state.newContextStore())
                .aggregateStore(state.newAggregateStore());
    }

    /**
     * Captures what this harness has persisted: every stored instance, every {@code SagaContext} record and a
     * copy of the aggregate store. Later units of work of this harness do not change the returned state.
     * Client-held state (handlers, repositories, participants) is not included; see {@link PersistedState}.
     *
     * @throws IllegalStateException if a unit of work is running (its uncommitted writes would be captured)
     */
    public PersistedState persistedState() {
        if (active != null) {
            throw new IllegalStateException("persistedState called while a unit of work is running");
        }
        return PersistedState.of(storage, contextStore, aggregates);
    }

    /**
     * Delivers {@code event} as if it arrived through the transport, expecting exactly one result.
     *
     * @throws IllegalArgumentException if {@code event} is {@code null}, not {@code @DomainEvent}-annotated, or
     *                                  lacks the association property a reacting step needs
     * @throws IllegalStateException    if the event affects more than one saga type / instance (use
     *                                  {@link #deliverAll(Object)}) or a unit of work is running
     */
    public UnitOfWorkResult deliver(Object event) {
        List<UnitOfWorkResult> results = deliverAll(event);
        if (results.size() != 1) {
            throw new IllegalStateException(
                    "Event " + event + " produced " + results.size() + " results; use deliverAll");
        }
        return results.get(0);
    }

    /**
     * Delivers {@code event} as if it arrived through the transport, to every registered saga type with a step
     * for its exact class, in saga registration order.
     *
     * @return one result per affected saga type / instance, or a single {@link Outcome#IGNORED} result
     * @throws IllegalArgumentException as for {@link #deliver(Object)}
     * @throws IllegalStateException    if a unit of work is running
     */
    public List<UnitOfWorkResult> deliverAll(Object event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (!event.getClass().isAnnotationPresent(DomainEvent.class)) {
            throw new IllegalArgumentException(event.getClass().getName() + " is not annotated @DomainEvent");
        }
        if (active != null) {
            throw new IllegalStateException("deliver called while a unit of work is running");
        }
        List<UnitOfWorkResult> results = new ArrayList<>();
        for (HarnessSagaModel saga : sagas.values()) {
            Optional<Step> step = saga.stepFor(event.getClass());
            if (step.isPresent()) {
                results.addAll(deliverTo(saga, step.get(), event));
            }
        }
        if (results.isEmpty()) {
            eventLog.add(Kind.IGNORED, event, null, null, null);
            results.add(UnitOfWorkResult.noUnitOfWork(Outcome.IGNORED, event, null, null));
        }
        return List.copyOf(results);
    }

    /** Outside-a-unit-of-work path of {@link HarnessEventPublisher}: dispatch now, then deliver once. */
    private List<UnitOfWorkResult> publishImmediately(Object event) {
        if (active != null) {
            throw new IllegalStateException("publish called outside the running unit of work's thread");
        }
        publishing = event;
        try {
            dispatcher.dispatch(event);
        } finally {
            publishing = null;
        }
        return deliverAll(event);
    }

    /** Dispatcher listener installed by {@link Builder#redeliverDispatched()}. */
    private void redeliver(Object event) {
        if (event != publishing) {
            deliverAll(event);
        }
    }

    private List<UnitOfWorkResult> deliverTo(HarnessSagaModel saga, Step step, Object event) {
        String value = HarnessReflection.readProperty(event, step.associationProperty())
                .map(String::valueOf)
                .orElseThrow(() -> new IllegalArgumentException("Event " + event + " has no value for association"
                        + " property '" + step.associationProperty() + "' of step " + step.name()));
        if (step.start()) {
            return List.of(runUnitOfWork(saga, step, event, null, value));
        }
        List<SagaInstance> correlated = storage.findByAssociation(step.associationProperty(), value).stream()
                .filter(instance -> instance.sagaType().equals(saga.type().getName()))
                .toList();
        List<SagaInstance> activeInstances = correlated.stream()
                .filter(instance -> !HarnessSagaLifecycle.isTerminal(instance.status()))
                .toList();
        if (!activeInstances.isEmpty()) {
            List<UnitOfWorkResult> results = new ArrayList<>();
            for (SagaInstance resolved : activeInstances) {
                // Re-read: an earlier unit of work of this loop may have changed the instance (for example via
                // redeliverDispatched), and a unit of work must start from the persisted state only.
                SagaInstance instance = storage.findById(resolved.sagaId()).orElse(null);
                if (instance == null || HarnessSagaLifecycle.isTerminal(instance.status())) {
                    boolean gone = instance == null;
                    String sagaId = gone ? null : instance.sagaId();
                    eventLog.add(gone ? Kind.UNMATCHED : Kind.UNEXPECTED, event, saga.type(), sagaId, step.name());
                    results.add(UnitOfWorkResult.noUnitOfWork(
                            gone ? Outcome.UNMATCHED : Outcome.UNEXPECTED, event, saga.type(), sagaId));
                    continue;
                }
                results.add(runUnitOfWork(saga, step, event, instance, value));
            }
            return results;
        }
        if (!correlated.isEmpty()) {
            String sagaId = correlated.get(correlated.size() - 1).sagaId();
            eventLog.add(Kind.UNEXPECTED, event, saga.type(), sagaId, step.name());
            return List.of(UnitOfWorkResult.noUnitOfWork(Outcome.UNEXPECTED, event, saga.type(), sagaId));
        }
        eventLog.add(Kind.UNMATCHED, event, saga.type(), null, step.name());
        return List.of(UnitOfWorkResult.noUnitOfWork(Outcome.UNMATCHED, event, saga.type(), null));
    }

    private UnitOfWorkResult runUnitOfWork(
            HarnessSagaModel saga, Step step, Object event, SagaInstance instance, String value) {
        if (active != null) {
            throw new IllegalStateException("a unit of work is already running (saga " + active.sagaId() + ")");
        }
        String guarded = instance == null ? null : instance.sagaId();
        if (guarded != null && !running.add(guarded)) {
            throw new IllegalStateException("a unit of work of saga instance " + guarded + " is already running");
        }
        HarnessUnitOfWork unitOfWork = new HarnessUnitOfWork(saga, step, event, instance, value, collaborators);
        active = unitOfWork;
        boolean committed;
        try {
            committed = unitOfWork.run();
        } finally {
            active = null;
            if (guarded != null) {
                running.remove(guarded);
            }
        }
        if (committed) {
            unitOfWork.dispatchPending();
        }
        return unitOfWork.result();
    }

    /**
     * The stored instance {@code sagaId}.
     *
     * @throws NoSuchElementException if no such instance is stored
     */
    public SagaInstance instance(String sagaId) {
        return findInstance(sagaId)
                .orElseThrow(() -> new NoSuchElementException("No saga instance '" + sagaId + "'"));
    }

    /** The stored instance {@code sagaId}, if any. */
    public Optional<SagaInstance> findInstance(String sagaId) {
        return storage.findById(sagaId);
    }

    /** Every stored instance of {@code sagaType}, in creation order. */
    public List<SagaInstance> instances(Class<?> sagaType) {
        return storage.all().stream().filter(i -> i.sagaType().equals(sagaType.getName())).toList();
    }

    /**
     * Read-only view of the {@code SagaContext} values stored for {@code sagaId} (structured values as
     * unmodifiable maps); empty if nothing was written.
     */
    public Map<String, Object> context(String sagaId) {
        return HarnessSagaContext.view(contextStore, sagaId);
    }

    /** Log of every delivered, in-process, dispatched, discarded, unmatched, unexpected and ignored event. */
    public HarnessEventLog eventLog() {
        return eventLog;
    }

    public InMemoryStorageProvider storage() {
        return storage;
    }

    public InMemorySagaContextStore contextStore() {
        return contextStore;
    }

    public InMemoryAggregateStore aggregates() {
        return aggregates;
    }

    /** After-commit dispatch log ({@link RecordingEventDispatcher#dispatched()}). */
    public RecordingEventDispatcher dispatcher() {
        return dispatcher;
    }

    public HarnessSagaLifecycle lifecycle() {
        return lifecycle;
    }

    public MapHandlerInstanceProvider handlers() {
        return handlers;
    }

    public HarnessCommandGateway commandGateway() {
        return gateway;
    }

    /**
     * The {@link AggregateResolver} used when no client loader / saver exists: the one passed to
     * {@link Builder#resolver(AggregateResolver)}, else an {@link InMemoryAggregateResolver} over
     * {@link #aggregates()} supporting the {@link Builder#resolvedAggregate(Class)} types.
     */
    public AggregateResolver resolver() {
        return resolver;
    }

    /** The harness' {@code EventPublisher}; see {@link HarnessEventPublisher}. */
    public HarnessEventPublisher eventPublisher() {
        return eventPublisher;
    }

    public Clock clock() {
        return clock;
    }

    /** Builder for {@link SagaTestHarness}. */
    public static final class Builder {
        private final List<Class<?>> sagaTypes = new ArrayList<>();
        private final List<Object> handlers = new ArrayList<>();
        private final List<Snapshottable> participants = new ArrayList<>();
        private final List<Class<?>> aggregateTypes = new ArrayList<>();
        private final List<Class<?>> resolvedAggregates = new ArrayList<>();
        private final List<Object> repositories = new ArrayList<>();
        private AggregateResolver resolver;
        private boolean redeliverDispatched;
        private Clock clock = Clock.fixed(DEFAULT_INSTANT, ZoneOffset.UTC);
        private Supplier<String> sagaIds = () -> UUID.randomUUID().toString();
        private InMemoryStorageProvider storage = new InMemoryStorageProvider();
        private InMemorySagaContextStore contextStore = new InMemorySagaContextStore();
        private InMemoryAggregateStore aggregates = new InMemoryAggregateStore();

        private Builder() {
        }

        /** Registers a {@code @Saga} class; validated at {@link #build()}. */
        public Builder saga(Class<?> sagaType) {
            sagaTypes.add(requireNonNull(sagaType, "sagaType"));
            return this;
        }

        /**
         * Registers a service-hosted handler instance: put in the {@link MapHandlerInstanceProvider} and scanned
         * for {@code @CommandHandler} methods at {@link #build()}. Snapshotted if it is {@link Snapshottable}.
         */
        public Builder handler(Object handler) {
            handlers.add(requireNonNull(handler, "handler"));
            return this;
        }

        /**
         * Registers an {@code @Aggregate} class whose {@code @CommandHandler} instance methods, constructors and
         * static factories become command routes at {@link #build()}.
         *
         * @throws IllegalArgumentException if {@code aggregateType} is {@code null}, not {@code @Aggregate}-annotated,
         *                                  or already registered
         */
        public Builder aggregate(Class<?> aggregateType) {
            requireNonNull(aggregateType, "aggregateType");
            if (!aggregateType.isAnnotationPresent(Aggregate.class)) {
                throw new IllegalArgumentException(aggregateType.getName() + " is not annotated @Aggregate");
            }
            if (aggregateTypes.contains(aggregateType)) {
                throw new IllegalArgumentException("Aggregate " + aggregateType.getName() + " registered twice");
            }
            aggregateTypes.add(aggregateType);
            return this;
        }

        /**
         * {@link #aggregate(Class)}, and makes the default {@link InMemoryAggregateResolver} support the type, so
         * it is loaded from and saved to {@link SagaTestHarness#aggregates()} when no client loader / saver exists.
         * Cannot be combined with {@link #resolver(AggregateResolver)}.
         */
        public Builder resolvedAggregate(Class<?> aggregateType) {
            aggregate(aggregateType);
            resolvedAggregates.add(aggregateType);
            return this;
        }

        /**
         * Registers a client object declaring {@code @AggregateLoader} and / or {@code @AggregateSaver} methods
         * (at most one of each per aggregate type, across all repositories); they take precedence over the
         * resolver. Snapshotted with each unit of work if it is {@link Snapshottable} (once, even if also a
         * handler).
         */
        public Builder repository(Object repository) {
            repositories.add(requireNonNull(repository, "repository"));
            return this;
        }

        /** Replaces the default in-memory resolver (host adapter stand-in). */
        public Builder resolver(AggregateResolver resolver) {
            this.resolver = requireNonNull(resolver, "resolver");
            return this;
        }

        /**
         * Opt-in: every event dispatched after a commit is also delivered back into the harness (as
         * {@link SagaTestHarness#deliverAll(Object)}) once the dispatching unit of work has finished. An event
         * published through {@link SagaTestHarness#eventPublisher()} is still delivered only once. Default: off.
         */
        public Builder redeliverDispatched() {
            this.redeliverDispatched = true;
            return this;
        }

        /** Registers extra state that is snapshotted at begin and restored on rollback of each unit of work. */
        public Builder participant(Snapshottable participant) {
            participants.add(requireNonNull(participant, "participant"));
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = requireNonNull(clock, "clock");
            return this;
        }

        public Builder sagaIds(Supplier<String> sagaIds) {
            this.sagaIds = requireNonNull(sagaIds, "sagaIds");
            return this;
        }

        public Builder storage(InMemoryStorageProvider storage) {
            this.storage = requireNonNull(storage, "storage");
            return this;
        }

        public Builder contextStore(InMemorySagaContextStore contextStore) {
            this.contextStore = requireNonNull(contextStore, "contextStore");
            return this;
        }

        public Builder aggregateStore(InMemoryAggregateStore aggregates) {
            this.aggregates = requireNonNull(aggregates, "aggregates");
            return this;
        }

        /**
         * @throws IllegalArgumentException if a saga is invalid or registered twice, a handler, loader or saver
         *                                  method has an unsupported shape, a command type has two handlers, an
         *                                  aggregate type has two loaders or two savers, an aggregate with
         *                                  instance-method handlers has neither a loader nor a supporting resolver
         *                                  (startup check), or {@code resolvedAggregate} is combined with
         *                                  {@code resolver}
         */
        public SagaTestHarness build() {
            return new SagaTestHarness(this);
        }

        private static <T> T requireNonNull(T value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " must not be null");
            }
            return value;
        }
    }
}
