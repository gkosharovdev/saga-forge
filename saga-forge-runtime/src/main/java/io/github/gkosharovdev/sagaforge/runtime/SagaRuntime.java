package io.github.gkosharovdev.sagaforge.runtime;

import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.event.EventPublisher;
import io.github.gkosharovdev.sagaforge.core.scanner.ReflectiveAnnotationScanner;
import io.github.gkosharovdev.sagaforge.core.scanner.ValidationReport;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.CommandReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.EventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.EventReceiver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingReport;
import io.github.gkosharovdev.sagaforge.core.spi.SagaContextStore;
import io.github.gkosharovdev.sagaforge.core.spi.SagaObjectProvider;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.core.spi.TransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.internal.Diagnostics;
import io.github.gkosharovdev.sagaforge.runtime.internal.EngineRuntime;
import io.github.gkosharovdev.sagaforge.runtime.internal.EngineSettings;
import io.github.gkosharovdev.sagaforge.runtime.internal.RetrySettings;
import io.github.gkosharovdev.sagaforge.runtime.internal.TimeAndIdentity;
import java.time.Clock;
import java.time.Duration;
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
 * SagaRuntime runtime = SagaRuntime.builder()
 *         .register(OrderProcessingSaga.class, Order.class, OrderRepository.class, PaymentService.class)
 *         .storage(storageProvider)
 *         .contextStore(sagaContextStore)
 *         .transactions(transactionBoundary)
 *         .dispatcher(eventDispatcher)
 *         .handlerInstances(handlerInstanceProvider)
 *         .build();
 * runtime.start();
 * }</pre>
 * <p>Host integrations build engines; application developers using the Spring Boot starter never do.</p>
 *
 * <h2>States</h2>
 * <p>{@code BUILT} → {@link #start()} → {@code STARTED}, or {@code REFUSED} if the model or plug-ins are not
 * runnable ({@link SagaRuntimeStartupException}). {@link #receive(Object)}, {@code commandGateway().send} and
 * {@code eventPublisher().publish} throw {@link IllegalStateException} unless the engine is {@code STARTED}; a
 * refused engine never accepts work.</p>
 *
 * <p>Thread-safe. Each call runs on the calling thread; the engine owns no thread.</p>
 */
public final class SagaRuntime implements EventReceiver {

    /** Name of the default {@link System.Logger}. */
    public static final String DEFAULT_LOGGER_NAME = "io.github.gkosharovdev.sagaforge.runtime";

    private enum State { BUILT, STARTED, REFUSED }

    private final List<Class<?>> registered;
    private final EngineSettings settings;
    private final Diagnostics diagnostics;
    private final CommandGateway commandGateway = command -> started().commandGateway().send(command);
    private final EventPublisher eventPublisher = event -> started().eventPublisher().publish(event);
    private final CommandReceiver commandReceiver = delivery -> started().receiveCommand(delivery);
    private final Object startLock = new Object();
    private volatile State state = State.BUILT;
    private volatile EngineRuntime runtime;

    private SagaRuntime(List<Class<?>> registered, EngineSettings settings, Diagnostics diagnostics) {
        this.registered = List.copyOf(registered);
        this.settings = settings;
        this.diagnostics = diagnostics;
    }

    /** A new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates the registered model with {@code ReflectiveAnnotationScanner.validateAll} and the engine's own checks
     * (aggregate loading, handler parameters, saga objects; contracts/engine-startup.md), all errors of both in one
     * report; then builds the saga models and command routes, registers every {@code @Saga} class with the
     * {@link EventDispatcher} in registration order, and starts accepting events (FR-034 to FR-036).
     *
     * @throws SagaRuntimeStartupException if the model or plug-ins are not runnable or the dispatcher rejects a saga
     *                                    type; the engine is then refused for good and processes nothing
     * @throws IllegalStateException      if {@code start()} was called before
     */
    public void start() {
        synchronized (startLock) {
            if (state != State.BUILT) {
                throw new IllegalStateException("start() was already called; the saga engine is " + state);
            }
            try {
                startAccepting(checked());
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

    /**
     * The engine's {@code CommandReceiver}, through which a transport delivers {@code @Command(delivery = OUTBOX)}
     * commands from its message store ({@code specs/007-in-memory-transport}); {@code receive} throws
     * {@link IllegalStateException} unless started.
     */
    public CommandReceiver commandReceiver() {
        return commandReceiver;
    }

    /** The engine's {@code EventPublisher}; {@code publish} throws {@link IllegalStateException} unless started. */
    public EventPublisher eventPublisher() {
        return eventPublisher;
    }

    /** {@code true} once {@link #start()} succeeded. */
    public boolean isStarted() {
        return state == State.STARTED;
    }

    /**
     * Runs {@code validateAll} and the engine's own checks together, so one refusal lists every error of both
     * (FR-034, FR-035).
     */
    private ValidationReport checked() {
        ValidationReport validation = new ReflectiveAnnotationScanner().validateAll(registered);
        StartupReport report = new StartupReport(validation, EngineRuntime.startupErrors(registered, settings));
        if (!report.isValid()) {
            throw refuse(report);
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
            SagaRuntimeStartupException refusal = refuse(new StartupReport(validation, List.of(error)));
            refusal.initCause(rejection);
            throw refusal;
        }
    }

    private SagaRuntimeStartupException refuse(StartupReport report) {
        state = State.REFUSED;
        diagnostics.startupRefused(report.allErrors());
        return new SagaRuntimeStartupException(report);
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
        private Outbox outbox;
        private Clock clock = Clock.systemUTC();
        private Supplier<String> sagaIds = Builder::randomId;
        private Supplier<String> unitOfWorkIds = Builder::randomId;
        private System.Logger logger = System.getLogger(DEFAULT_LOGGER_NAME);
        private Duration maxTotalRetryBackoff = RetrySettings.DEFAULT_MAX_TOTAL_BACKOFF;
        private RetryPause retryPause = RetryPause.sleeping();

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

        /** Optional: receives committed events; default {@link ImmediateDispatchOutbox} over the dispatcher. */
        public Builder outbox(Outbox outbox) {
            this.outbox = requireNonNull(outbox, "outbox");
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

        /**
         * The most a step's {@code @RetryPolicy} may wait in total over all its attempts; {@code start()} refuses a
         * step whose policy exceeds it ({@code specs/005-step-retry} FR-014a). Default: 30 seconds.
         *
         * @throws IllegalArgumentException if {@code maxTotalRetryBackoff} is {@code null}, zero or negative
         */
        public Builder maxTotalRetryBackoff(Duration maxTotalRetryBackoff) {
            if (maxTotalRetryBackoff == null || maxTotalRetryBackoff.isNegative() || maxTotalRetryBackoff.isZero()) {
                throw new IllegalArgumentException("maxTotalRetryBackoff must be positive, but is "
                        + maxTotalRetryBackoff);
            }
            this.maxTotalRetryBackoff = maxTotalRetryBackoff;
            return this;
        }

        /**
         * How the engine waits between attempts of a retried unit of work. Default: {@link RetryPause#sleeping()}.
         *
         * @throws IllegalArgumentException if {@code retryPause} is {@code null}
         */
        public Builder retryPause(RetryPause retryPause) {
            this.retryPause = requireNonNull(retryPause, "retryPause");
            return this;
        }

        /** Optional: where diagnostics go; default {@code System.getLogger(DEFAULT_LOGGER_NAME)}. */
        public Builder logger(System.Logger logger) {
            this.logger = requireNonNull(logger, "logger");
            return this;
        }

        /**
         * Assembles the engine, which still has to be {@linkplain SagaRuntime#start() started}. Reads the
         * outbox's {@code timing()} once.
         *
         * @throws IllegalStateException if no class was registered, a required plug-in is missing, or the
         *                               outbox reports a {@code null} timing
         */
        public SagaRuntime build() {
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
            return new SagaRuntime(List.copyOf(registered), settings(), new Diagnostics(logger));
        }

        private EngineSettings settings() {
            Outbox effectiveOutbox = outbox != null ? outbox : new ImmediateDispatchOutbox(dispatcher);
            Outbox.Timing timing = effectiveOutbox.timing();
            if (timing == null) {
                throw new IllegalStateException("The Outbox reported a null timing()");
            }
            HandlerInstanceProvider instances = handlerInstances != null
                    ? handlerInstances
                    : EngineSettings.noHandlerInstances();
            return new EngineSettings(storage, contextStore, transactions, dispatcher, instances, aggregateResolvers,
                    parameterResolvers, Optional.ofNullable(sagaObjects), effectiveOutbox, timing,
                    new TimeAndIdentity(clock, sagaIds, unitOfWorkIds),
                    new RetrySettings(maxTotalRetryBackoff, retryPause::pause));
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
