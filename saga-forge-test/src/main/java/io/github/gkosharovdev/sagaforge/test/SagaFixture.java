package io.github.gkosharovdev.sagaforge.test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;

/**
 * A given/when/then test fixture for sagas ({@code specs/009-saga-test-fixture}). It builds and starts the shipped
 * {@link SagaRuntime} over in-memory plug-ins of its own, so a test runs the real saga semantics (routing by
 * association, the saga-scoped unit of work, duplicates, retries, compensation) with no database, transport or Spring
 * context.
 *
 * <pre>{@code
 * SagaFixture fixture = SagaFixture.of(OrderProcessingSaga.class).build();
 * fixture.given(new OrderCreatedEvent("o-1", "10"))
 *        .when(new PaymentProcessedEvent("payment-1", "o-1", "10"))
 *        .expectCommands(new ReserveStockCommand("o-1"))
 *        .expectContext("paymentId", "payment-1")
 *        .expectCompletedStep("processPayment")
 *        .expectStatus(SagaStatus.RUNNING);
 * }</pre>
 *
 * <h2>Record and answer, or handler mode</h2>
 * <p>By default no command handler runs: every command a step sends is recorded and counts as handled, and the test
 * answers it by publishing the resulting event (FR-011). With {@link Builder#handlers(Object...)} (or
 * {@link Builder#handleAggregates()}), the registered hosts' handlers and the registered aggregates' handlers run as in
 * production, with aggregates in an in-memory store the test seeds and reads (FR-012).</p>
 *
 * <h2>Delivery, time and identities</h2>
 * <p>Events a unit of work records for other instances or saga types, and {@code @Command(delivery = OUTBOX)}
 * commands, are delivered after the commit, synchronously, before {@code given}/{@code when} returns. Retry waits
 * advance the {@link FixtureClock} instead of sleeping. Saga ids are {@code saga-1}, {@code saga-2}, ... and
 * unit-of-work ids {@code uow-1}, ..., so command identities are deterministic (FR-009, FR-010).</p>
 *
 * <p>One fixture is one scenario; fixtures share nothing, so tests may run in parallel (FR-016). A fixture runs one
 * event at a time.</p>
 */
public final class SagaFixture {

    private final SagaRuntime runtime;
    private final FixtureClock clock;
    private final FixtureStorage storage;
    private final FixtureContextStore contexts;
    private final AggregateStore aggregates;
    private final FixtureCommandInterceptor interceptor;
    private final LoopBackTransport transport;
    private final FixtureLogger logger;
    private final boolean handlerMode;
    private final Set<Class<?>> aggregateTypes;
    private final Map<String, Map<String, String>> compensations;

    private SagaFixture(SagaRuntime runtime, FixtureClock clock, FixtureStorage storage, FixtureContextStore contexts,
            AggregateStore aggregates, FixtureCommandInterceptor interceptor, LoopBackTransport transport,
            FixtureLogger logger, boolean handlerMode, Set<Class<?>> aggregateTypes,
            Map<String, Map<String, String>> compensations) {
        this.runtime = runtime;
        this.clock = clock;
        this.storage = storage;
        this.contexts = contexts;
        this.aggregates = aggregates;
        this.interceptor = interceptor;
        this.transport = transport;
        this.logger = logger;
        this.handlerMode = handlerMode;
        this.aggregateTypes = aggregateTypes;
        this.compensations = compensations;
    }

    /**
     * A builder over {@code modelClasses}: sagas and any other model classes (aggregates, loader/saver hosts), as
     * {@code SagaRuntime.Builder.register}.
     *
     * @throws IllegalArgumentException if a class is {@code null}
     */
    public static Builder of(Class<?>... modelClasses) {
        return new Builder().register(modelClasses);
    }

    /**
     * Publishes history: each event runs exactly as a published event, but does not count towards expectations
     * (FR-004).
     *
     * @throws SagaExpectationError if a step stored a {@code SagaContext} value durable storage cannot persist
     */
    public Given given(Object... events) {
        return new Given(this).given(events);
    }

    /**
     * Seeds aggregates into the in-memory store (handler mode only).
     *
     * @throws IllegalStateException    outside handler mode
     * @throws IllegalArgumentException for an object that is not an {@code @Aggregate} registered with the fixture
     */
    public Given givenAggregates(Object... aggregates) {
        return new Given(this).givenAggregates(aggregates);
    }

    /**
     * Publishes the event under test, with no prior history.
     *
     * @throws SagaExpectationError if a step stored a {@code SagaContext} value durable storage cannot persist
     */
    public Then when(Object event) {
        return new Then(this, publish(event), null);
    }

    /**
     * The most recently created instance of {@code sagaType} with {@code associationValue} as one of its association
     * values.
     *
     * @throws SagaExpectationError if there is none
     */
    public InstanceView instance(Class<?> sagaType, String associationValue) {
        return findInstance(sagaType, associationValue).orElseThrow(() -> new SagaExpectationError("No "
                + sagaType.getSimpleName() + " instance is associated with '" + associationValue + "'; instances: "
                + storage.all().stream().map(instance -> view(instance).describe()).toList()));
    }

    /** The aggregate of {@code type} with identifier {@code id} in the fixture's store, as last saved. */
    public <T> Optional<T> aggregate(Class<T> type, Object id) {
        return aggregates.find(type, id);
    }

    /** The time the runtime observes; a test may {@linkplain FixtureClock#set set} or advance it. */
    public FixtureClock clock() {
        return clock;
    }

    /** Every command handled in a committed unit of work so far, history included. */
    public List<RecordedCommand> commands() {
        return interceptor.committedSnapshot();
    }

    // --- package-private: used by Given and Then -------------------------------------------------------------------

    synchronized EventResult publish(Object event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        Map<String, InstanceView> before = new LinkedHashMap<>();
        storage.all().forEach(instance -> before.put(instance.sagaId(), view(instance)));
        int committedMark = interceptor.committedSnapshot().size();
        int attemptsMark = interceptor.attemptsSnapshot().size();
        int loggedMark = logger.logged().size();
        contexts.takeRejections();

        List<HandlingReport> reports = new ArrayList<>();
        List<RuntimeException> outboxFailures = new ArrayList<>();
        reports.add(runtime.receive(event));
        transport.drain(runtime, interceptor, reports::add, outboxFailures::add);

        Set<String> touchedIds = new LinkedHashSet<>();
        for (HandlingReport report : reports) {
            for (SagaHandlingResult result : report.results()) {
                if (result.sagaId() != null) {
                    touchedIds.add(result.sagaId());
                }
            }
        }
        List<InstanceView> touched = touchedIds.stream().map(storage::findById).flatMap(Optional::stream)
                .map(this::view).toList();
        List<RecordedCommand> committed = interceptor.committedSnapshot();
        List<RecordedCommand> attempts = interceptor.attemptsSnapshot();
        List<FixtureLogger.Logged> logged = logger.logged();
        EventResult result = new EventResult(event, reports, committed.subList(committedMark, committed.size()),
                attempts.subList(attemptsMark, attempts.size()), before, touched, outboxFailures,
                logged.subList(loggedMark, logged.size()));
        List<UnsupportedContextValueException> rejected = contexts.takeRejections();
        if (!rejected.isEmpty()) {
            throw Messages.unsupportedContextValue(this, result, rejected.get(0));
        }
        return result;
    }

    synchronized void seed(Object... seeded) {
        if (!handlerMode) {
            throw new IllegalStateException("givenAggregates(...) needs handler mode: build the fixture with"
                    + " handlers(...) or handleAggregates()");
        }
        if (seeded == null) {
            throw new IllegalArgumentException("aggregates must not be null");
        }
        for (Object aggregate : seeded) {
            if (aggregate == null || !aggregateTypes.contains(aggregate.getClass())) {
                throw new IllegalArgumentException((aggregate == null ? "null" : aggregate.getClass().getName())
                        + " is not an @Aggregate registered with the fixture; register its class");
            }
            aggregates.seed(aggregate);
        }
    }

    Optional<InstanceView> findInstance(Class<?> sagaType, String associationValue) {
        if (sagaType == null || associationValue == null) {
            throw new IllegalArgumentException("sagaType and associationValue must not be null");
        }
        return storage.all().stream()
                .filter(instance -> instance.sagaType().equals(sagaType.getName()))
                .filter(instance -> instance.associations().containsValue(associationValue))
                .max(Comparator.comparing(SagaInstance::createdAt))
                .map(this::view);
    }

    Optional<InstanceView> current(String sagaId) {
        return storage.findById(sagaId).map(this::view);
    }

    /** Compensation method name of {@code step} in {@code sagaType}, or the step name if none is declared. */
    String compensationOf(String sagaType, String step) {
        return compensations.getOrDefault(sagaType, Map.of()).getOrDefault(step, step);
    }

    private InstanceView view(SagaInstance instance) {
        return new InstanceView(instance, contexts.values(instance.sagaId()));
    }

    /**
     * Collects the model, handlers, failure rules and settings of one fixture ({@code specs/009-saga-test-fixture}
     * contracts/fixture-api.md). Setters reject {@code null} with {@link IllegalArgumentException}. Not thread-safe.
     */
    public static final class Builder {

        private final Set<Class<?>> model = new LinkedHashSet<>();
        private final Map<Class<?>, Object> hosts = new LinkedHashMap<>();
        private final Map<Class<?>, Supplier<?>> sagaFactories = new LinkedHashMap<>();
        private final List<CommandFailureRule> rules = new ArrayList<>();
        private Instant start = FixtureClock.DEFAULT_START;
        private Duration maxTotalRetryBackoff;
        private boolean checkContextValues = true;
        private boolean handleAggregates;

        private Builder() {
        }

        /** Further model classes; repeatable, order kept, repeats ignored. */
        public Builder register(Class<?>... classes) {
            requireNonNull(classes, "classes");
            for (Class<?> type : classes) {
                model.add(requireNonNull(type, "model class"));
            }
            return this;
        }

        /**
         * Runs the {@code @CommandHandler}s, {@code @AggregateLoader}s and {@code @AggregateSaver}s of these objects,
         * and the handlers of the registered aggregates, as in production (handler mode, FR-012). Each object's class
         * is registered. Commands without a registered handler are still only recorded.
         */
        public Builder handlers(Object... handlerHosts) {
            requireNonNull(handlerHosts, "handlerHosts");
            for (Object host : handlerHosts) {
                requireNonNull(host, "handler host");
                model.add(host.getClass());
                hosts.put(host.getClass(), host);
            }
            return this;
        }

        /** Handler mode without a service host: the registered aggregates' handlers run on the in-memory store. */
        public Builder handleAggregates() {
            this.handleAggregates = true;
            return this;
        }

        /**
         * Supplies objects of {@code sagaType}, for a saga that needs constructor arguments (FR-013); asked once per
         * unit of work. Other sagas are created with their no-argument constructor.
         */
        public <T> Builder sagaObjects(Class<T> sagaType, Supplier<? extends T> factory) {
            requireNonNull(sagaType, "sagaType");
            requireNonNull(factory, "factory");
            if (!sagaType.isAnnotationPresent(Saga.class)) {
                throw new IllegalArgumentException(sagaType.getName() + " is not a @Saga");
            }
            model.add(sagaType);
            sagaFactories.put(sagaType, factory);
            return this;
        }

        /** Every handling of a {@code commandType} command fails with {@code failure} (FR-008). */
        public <T> Builder failCommand(Class<T> commandType, Supplier<? extends RuntimeException> failure) {
            return failCommand(commandType, command -> true, failure, CommandFailureRule.ALWAYS);
        }

        /** The next {@code times} handlings of a {@code commandType} command fail with {@code failure}. */
        public <T> Builder failCommand(Class<T> commandType, Supplier<? extends RuntimeException> failure, int times) {
            return failCommand(commandType, command -> true, failure, requirePositive(times));
        }

        /** Every handling of a {@code commandType} command matching {@code matching} fails with {@code failure}. */
        public <T> Builder failCommand(Class<T> commandType, Predicate<? super T> matching,
                Supplier<? extends RuntimeException> failure) {
            return failCommand(commandType, matching, failure, CommandFailureRule.ALWAYS);
        }

        /**
         * The next {@code times} handlings of a {@code commandType} command matching {@code matching} fail with
         * {@code failure}. Rules apply in the order given; the first match wins.
         */
        public <T> Builder failCommand(Class<T> commandType, Predicate<? super T> matching,
                Supplier<? extends RuntimeException> failure, int times) {
            requireNonNull(commandType, "commandType");
            requireNonNull(matching, "matching");
            requireNonNull(failure, "failure");
            if (times != CommandFailureRule.ALWAYS) {
                requirePositive(times);
            }
            rules.add(new CommandFailureRule(commandType, matching, failure, times));
            return this;
        }

        /** Where the {@link FixtureClock} starts; default {@link FixtureClock#DEFAULT_START}. */
        public Builder startingAt(Instant instant) {
            this.start = requireNonNull(instant, "instant");
            return this;
        }

        /** As {@code SagaRuntime.Builder.maxTotalRetryBackoff}; default the runtime's 30 seconds. */
        public Builder maxTotalRetryBackoff(Duration maximum) {
            requireNonNull(maximum, "maximum");
            if (maximum.isNegative() || maximum.isZero()) {
                throw new IllegalArgumentException("maxTotalRetryBackoff must be positive, but is " + maximum);
            }
            this.maxTotalRetryBackoff = maximum;
            return this;
        }

        /**
         * Whether a {@code SagaContext} value durable storage cannot persist (a {@code BigDecimal}, say) fails the
         * test (FR-019); default {@code true}.
         */
        public Builder checkContextValues(boolean check) {
            this.checkContextValues = check;
            return this;
        }

        /**
         * Builds the runtime over the fixture's plug-ins and starts it, validating the model as the runtime's startup
         * does (FR-003).
         *
         * @throws IllegalStateException        if no class was registered
         * @throws SagaRuntimeStartupException if the model is invalid, with every error
         */
        public SagaFixture build() {
            if (model.isEmpty()) {
                throw new IllegalStateException("No class was registered; use SagaFixture.of(YourSaga.class)");
            }
            FixtureClock clock = new FixtureClock(start);
            FixtureTransactions transactions = new FixtureTransactions();
            FixtureStorage storage = new FixtureStorage();
            transactions.participant(storage);
            Set<Class<?>> aggregateTypes = new LinkedHashSet<>();
            model.stream().filter(Model::isAggregate).forEach(aggregateTypes::add);
            AggregateStore aggregates = new AggregateStore(aggregateTypes);
            transactions.participant(aggregates);

            boolean handlerMode = !hosts.isEmpty() || handleAggregates;
            Set<Class<?>> handledTypes = new LinkedHashSet<>();
            hosts.keySet().forEach(host -> handledTypes.addAll(Model.handledCommandTypes(host)));
            if (handlerMode) {
                aggregateTypes.forEach(aggregate -> handledTypes.addAll(Model.handledCommandTypes(aggregate)));
            }
            FixtureContextStore contexts = new FixtureContextStore(checkContextValues,
                    sagaId -> storage.findById(sagaId).map(SagaInstance::sagaType).orElse("unknown"));
            FixtureCommandInterceptor interceptor = new FixtureCommandInterceptor(transactions, rules, handledTypes);
            LoopBackTransport transport = new LoopBackTransport();
            FixtureLogger logger = new FixtureLogger(System.getLogger(SagaRuntime.DEFAULT_LOGGER_NAME));
            FixtureObjects objects = new FixtureObjects(sagaFactories, hosts);
            Map<String, Map<String, String>> compensations = new LinkedHashMap<>();
            model.stream().filter(type -> type.isAnnotationPresent(Saga.class))
                    .forEach(saga -> compensations.put(saga.getName(), Model.compensations(saga)));

            AtomicInteger sagaIds = new AtomicInteger();
            AtomicInteger unitOfWorkIds = new AtomicInteger();
            SagaRuntime.Builder runtime = SagaRuntime.builder()
                    .register(model.toArray(Class<?>[]::new))
                    .storage(storage)
                    .contextStore(contexts)
                    .transactions(transactions)
                    .dispatcher(transport)
                    .outbox(transport)
                    .handlerInstances(objects)
                    .sagaObjects(objects)
                    .aggregateResolver(aggregates)
                    .clock(clock)
                    .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                    .unitOfWorkIds(() -> "uow-" + unitOfWorkIds.incrementAndGet())
                    .retryPause(clock::advance)
                    .commandInterceptor(interceptor)
                    .logger(logger);
            if (maxTotalRetryBackoff != null) {
                runtime.maxTotalRetryBackoff(maxTotalRetryBackoff);
            }
            SagaRuntime started = runtime.build();
            started.start();
            return new SagaFixture(started, clock, storage, contexts, aggregates, interceptor, transport, logger,
                    handlerMode, Set.copyOf(aggregateTypes), Map.copyOf(compensations));
        }

        private static int requirePositive(int times) {
            if (times < 1) {
                throw new IllegalArgumentException("times must be at least 1, but is " + times);
            }
            return times;
        }

        private static <T> T requireNonNull(T argument, String name) {
            if (argument == null) {
                throw new IllegalArgumentException(name + " must not be null");
            }
            return argument;
        }
    }
}
