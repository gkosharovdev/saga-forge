package io.github.gkosharovdev.sagaforge.engine;

import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.scanner.ReflectiveAnnotationScanner;
import io.github.gkosharovdev.sagaforge.core.scanner.ValidationReport;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.EventHandoff;
import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.internal.Diagnostics;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineRuntime;
import io.github.gkosharovdev.sagaforge.engine.internal.EngineSettings;
import io.github.gkosharovdev.sagaforge.engine.internal.TimeAndIdentity;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The saga runtime: receives domain events from a transport, routes them to saga types and instances, and runs each
 * unit of work in the host's local transaction (contracts/engine-startup.md, contracts/event-receiver.md).
 *
 * <h2>Assembly</h2>
 * <pre>{@code
 * SagaEngine engine = SagaEngine.builder()
 *         .register(OrderProcessingSaga.class, Order.class, OrderRepository.class, PaymentService.class)
 *         .storage(storageProvider)
 *         .contextStore(sagaContextStore)
 *         .transactions(transactionBoundary)
 *         .dispatcher(eventDispatcher)
 *         .handlerInstances(handlerInstanceProvider)
 *         .build();
 * engine.start();
 * }</pre>
 * <p>Host integrations build engines; application developers using the Spring Boot starter never do.</p>
 *
 * <h2>States</h2>
 * <p>{@code BUILT} → {@link #start()} → {@code STARTED}, or {@code REFUSED} if the model or plug-ins are not
 * runnable ({@link SagaEngineStartupException}). {@link #receive(Object)}, {@code commandGateway().send} and
 * {@code eventPublisher().publish} throw {@link IllegalStateException} unless the engine is {@code STARTED}; a
 * refused engine never accepts work.</p>
 *
 * <p>Thread-safe. Each call runs on the calling thread; the engine owns no thread.</p>
 */
public final class SagaEngine implements EventReceiver {

    /** Name of the default {@link System.Logger}. */
    public static final String DEFAULT_LOGGER_NAME = "io.github.gkosharovdev.sagaforge.engine";

    private enum State { BUILT, STARTED, REFUSED }

    private final List<Class<?>> registered;
    private final EngineSettings settings;
    private final Diagnostics diagnostics;
    private final CommandGateway commandGateway = command -> started().commandGateway().send(command);
    private final EventPublisher eventPublisher = event -> started().eventPublisher().publish(event);
    private final Object startLock = new Object();
    private volatile State state = State.BUILT;
    private volatile EngineRuntime runtime;

    private SagaEngine(List<Class<?>> registered, EngineSettings settings, Diagnostics diagnostics) {
        this.registered = List.copyOf(registered);
        this.settings = settings;
        this.diagnostics = diagnostics;
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates the registered model with {@code ReflectiveAnnotationScanner.validateAll}, builds the saga models
     * and command routes, registers every {@code @Saga} class with the {@link EventDispatcher} in registration
     * order, and starts accepting events (FR-034, FR-036).
     *
     * @throws SagaEngineStartupException if the model is invalid or the dispatcher rejects a saga type; the engine
     *                                    is then refused for good
     * @throws IllegalStateException      if {@code start()} was called before
     */
    public void start() {
        synchronized (startLock) {
            if (state != State.BUILT) {
                throw new IllegalStateException("start() was already called; the saga engine is " + state);
            }
            try {
                startAccepting(validated());
            } catch (RuntimeException | Error failure) {
                state = State.REFUSED;
                throw failure;
            }
        }
    }

    /**
     * Handles one inbound domain event (contracts/event-receiver.md).
     *
     * @throws IllegalStateException    unless the engine is {@code STARTED}
     * @throws IllegalArgumentException for a {@code null} or non-{@code @DomainEvent} event, or a missing
     *                                  association value
     */
    @Override
    public HandlingReport receive(Object domainEvent) {
        return started().receive(domainEvent);
    }

    /** The engine's {@code CommandGateway}; {@code send} throws {@link IllegalStateException} unless started. */
    public CommandGateway commandGateway() {
        return commandGateway;
    }

    /** The engine's {@code EventPublisher}; {@code publish} throws {@link IllegalStateException} unless started. */
    public EventPublisher eventPublisher() {
        return eventPublisher;
    }

    /** {@code true} once {@link #start()} succeeded. */
    public boolean isStarted() {
        return state == State.STARTED;
    }

    private ValidationReport validated() {
        ValidationReport validation = new ReflectiveAnnotationScanner().validateAll(registered);
        if (!validation.isValid()) {
            throw refuse(new StartupReport(validation, List.of()));
        }
        return validation;
    }

    private void startAccepting(ValidationReport validation) {
        EngineRuntime assembled = EngineRuntime.assemble(registered, settings, diagnostics);
        assembled.sagaTypes().forEach(sagaType -> registerWithDispatcher(sagaType, validation));
        runtime = assembled;
        state = State.STARTED;
        diagnostics.startupStarted(assembled.sagaTypes().stream().map(Class::getName).toList());
    }

    private void registerWithDispatcher(Class<?> sagaType, ValidationReport validation) {
        try {
            settings.dispatcher().registerSagaType(sagaType);
        } catch (RuntimeException rejection) {
            String error = "EventDispatcher rejected @Saga class " + sagaType.getSimpleName() + ": "
                    + rejection.getMessage();
            SagaEngineStartupException refusal = refuse(new StartupReport(validation, List.of(error)));
            refusal.initCause(rejection);
            throw refusal;
        }
    }

    private SagaEngineStartupException refuse(StartupReport report) {
        state = State.REFUSED;
        diagnostics.startupRefused(report.allErrors());
        return new SagaEngineStartupException(report);
    }

    private EngineRuntime started() {
        if (state != State.STARTED) {
            throw new IllegalStateException("The saga engine is " + state
                    + "; it accepts work only after a successful start()");
        }
        return runtime;
    }

    /**
     * Collects the classes and plug-ins of one engine (contracts/engine-startup.md "Builder"). Setters reject
     * {@code null} with {@link IllegalArgumentException}; {@link #build()} checks only that the required inputs
     * are present. Not thread-safe.
     */
    public static final class Builder {

        private final Set<Class<?>> registered = new LinkedHashSet<>();
        private final List<AggregateResolver> aggregateResolvers = new ArrayList<>();
        private final List<HandlerParameterResolver> parameterResolvers = new ArrayList<>();
        private StorageProvider storage;
        private SagaContextStore contextStore;
        private TransactionBoundary transactions;
        private EventDispatcher dispatcher;
        private HandlerInstanceProvider handlerInstances;
        private SagaObjectProvider sagaObjects;
        private EventHandoff handoff;
        private Clock clock = Clock.systemUTC();
        private Supplier<String> sagaIds = Builder::randomId;
        private Supplier<String> unitOfWorkIds = Builder::randomId;
        private System.Logger logger = System.getLogger(DEFAULT_LOGGER_NAME);

        private Builder() {
        }

        /**
         * Registers sagas, aggregates, and the classes hosting command handlers, loaders and savers; events and
         * commands may be listed or are reached through signatures. Repeatable; order is kept, repeats ignored.
         */
        public Builder register(Class<?>... classes) {
            requireNonNull(classes, "classes");
            for (Class<?> type : classes) {
                registered.add(requireNonNull(type, "registered class"));
            }
            return this;
        }

        /** Required: where saga instances and their lifecycle history are stored. */
        public Builder storage(StorageProvider storage) {
            this.storage = requireNonNull(storage, "storage");
            return this;
        }

        /** Required: where {@code SagaContext} values are stored. */
        public Builder contextStore(SagaContextStore contextStore) {
            this.contextStore = requireNonNull(contextStore, "contextStore");
            return this;
        }

        /** Required: the host's local transactions. */
        public Builder transactions(TransactionBoundary transactions) {
            this.transactions = requireNonNull(transactions, "transactions");
            return this;
        }

        /** Required: the outbound transport. */
        public Builder dispatcher(EventDispatcher dispatcher) {
            this.dispatcher = requireNonNull(dispatcher, "dispatcher");
            return this;
        }

        /** Required if a registered class hosts a service handler, loader or saver. */
        public Builder handlerInstances(HandlerInstanceProvider handlerInstances) {
            this.handlerInstances = requireNonNull(handlerInstances, "handlerInstances");
            return this;
        }

        /** Adds a host aggregate resolver; repeatable, the first supporting one wins. */
        public Builder aggregateResolver(AggregateResolver resolver) {
            aggregateResolvers.add(requireNonNull(resolver, "aggregateResolver"));
            return this;
        }

        /** Adds a resolver of non-command handler parameters; repeatable, the first supporting one wins. */
        public Builder parameterResolver(HandlerParameterResolver resolver) {
            parameterResolvers.add(requireNonNull(resolver, "parameterResolver"));
            return this;
        }

        /** Optional: supplies saga objects; default: their no-argument constructor. */
        public Builder sagaObjects(SagaObjectProvider sagaObjects) {
            this.sagaObjects = requireNonNull(sagaObjects, "sagaObjects");
            return this;
        }

        /** Optional: receives committed events; default {@link DispatchingEventHandoff} over the dispatcher. */
        public Builder handoff(EventHandoff handoff) {
            this.handoff = requireNonNull(handoff, "handoff");
            return this;
        }

        /** Optional: timestamps; default the system UTC clock. */
        public Builder clock(Clock clock) {
            this.clock = requireNonNull(clock, "clock");
            return this;
        }

        /** Optional: a fresh saga id per new instance; default random UUIDs. */
        public Builder sagaIds(Supplier<String> sagaIds) {
            this.sagaIds = requireNonNull(sagaIds, "sagaIds");
            return this;
        }

        /** Optional: a fresh id per unit of work; default random UUIDs. */
        public Builder unitOfWorkIds(Supplier<String> unitOfWorkIds) {
            this.unitOfWorkIds = requireNonNull(unitOfWorkIds, "unitOfWorkIds");
            return this;
        }

        /** Optional: where diagnostics go; default {@code System.getLogger(DEFAULT_LOGGER_NAME)}. */
        public Builder logger(System.Logger logger) {
            this.logger = requireNonNull(logger, "logger");
            return this;
        }

        /**
         * Assembles the engine, which still has to be {@linkplain SagaEngine#start() started}. Reads the
         * handoff's {@code timing()} once.
         *
         * @throws IllegalStateException if no class was registered, a required plug-in is missing, or the
         *                               handoff reports a {@code null} timing
         */
        public SagaEngine build() {
            if (registered.isEmpty()) {
                throw new IllegalStateException("No class was registered");
            }
            requirePresent(storage, "storage");
            requirePresent(contextStore, "contextStore");
            requirePresent(transactions, "transactions");
            requirePresent(dispatcher, "dispatcher");
            if (handlerInstances == null && EngineRuntime.needsHandlerInstances(registered)) {
                throw new IllegalStateException("A registered class hosts a service @CommandHandler, an"
                        + " @AggregateLoader or an @AggregateSaver, but no handlerInstances(...) was given");
            }
            return new SagaEngine(List.copyOf(registered), settings(), new Diagnostics(logger));
        }

        private EngineSettings settings() {
            EventHandoff effectiveHandoff = handoff != null ? handoff : new DispatchingEventHandoff(dispatcher);
            EventHandoff.Timing timing = effectiveHandoff.timing();
            if (timing == null) {
                throw new IllegalStateException("The EventHandoff reported a null timing()");
            }
            HandlerInstanceProvider instances = handlerInstances != null
                    ? handlerInstances
                    : EngineSettings.noHandlerInstances();
            return new EngineSettings(storage, contextStore, transactions, dispatcher, instances, aggregateResolvers,
                    parameterResolvers, Optional.ofNullable(sagaObjects), effectiveHandoff, timing,
                    new TimeAndIdentity(clock, sagaIds, unitOfWorkIds));
        }

        private static String randomId() {
            return UUID.randomUUID().toString();
        }

        private static void requirePresent(Object input, String name) {
            if (input == null) {
                throw new IllegalStateException(name + "(...) is required");
            }
        }

        private static <T> T requireNonNull(T argument, String name) {
            if (argument == null) {
                throw new IllegalArgumentException(name + " must not be null");
            }
            return argument;
        }
    }
}
