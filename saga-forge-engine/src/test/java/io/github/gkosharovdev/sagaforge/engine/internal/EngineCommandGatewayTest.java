package io.github.gkosharovdev.sagaforge.engine.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.Aggregate;
import io.github.gkosharovdev.sagaforge.core.annotation.AggregateIdentifier;
import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.AggregateNotFoundException;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.command.NoCommandHandlerException;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.AvailabilityClaimedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingConfirmedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.ClaimAvailability;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.ConfirmBooking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InitiatePayment;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentInitiatedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentService;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.execution.harness.RecordingEventDispatcher;
import io.github.gkosharovdev.sagaforge.core.spi.AggregateResolver;
import io.github.gkosharovdev.sagaforge.core.spi.DomainEventScope;
import io.github.gkosharovdev.sagaforge.core.spi.Outbox;
import io.github.gkosharovdev.sagaforge.core.spi.HandlerParameterResolver;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.engine.support.CapturingOutbox;
import io.github.gkosharovdev.sagaforge.engine.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.engine.support.TransactionalInMemoryStorageProvider;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link EngineCommandGateway} inside a bound unit of work, over the core test-jar booking fixtures
 * (specs/004-saga-engine tasks.md T045, 002 contracts/command-gateway.md, FR-016, FR-019).
 */
class EngineCommandGatewayTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 3, 1);
    private static final LocalDate CHECK_OUT = LocalDate.of(2026, 3, 4);

    // ---- test-only model: an aggregate served by host resolvers, and a handler with a non-command parameter

    /** Command for {@link Counter}, loaded by its identifier through a resolver. */
    @Command
    record Increment(@AggregateIdentifier String counterId) {
    }

    /** An aggregate with no client loader or saver: only host resolvers serve it. */
    @Aggregate
    static final class Counter {

        @AggregateIdentifier
        private final String counterId;
        private int value;

        Counter(String counterId) {
            this.counterId = counterId;
        }

        @CommandHandler
        public void handle(Increment command) {
            value++;
        }
    }

    /** A collaborator no @Command is, supplied to {@link AuditService} by a {@link HandlerParameterResolver}. */
    static final class Auditor {

        private final List<String> audited = new ArrayList<>();
    }

    /** Command for {@link AuditService}. */
    @Command
    record Audit(String subject) {
    }

    /** A command no handler takes. */
    @Command
    record Unrouted(String id) {
    }

    /** A plain object that is not a {@code @Command}. */
    record NotACommand(String id) {
    }

    /** A service handler whose second parameter comes from a parameter resolver. */
    public static final class AuditService {

        @CommandHandler
        public void handle(Audit command, Auditor auditor) {
            auditor.audited.add(command.subject());
        }
    }

    /** Resolves {@link Auditor} parameters to a fixed instance. */
    private static final class AuditorResolver implements HandlerParameterResolver {

        private final Auditor auditor;

        private AuditorResolver(Auditor auditor) {
            this.auditor = auditor;
        }

        @Override
        public boolean supports(Executable handler, Parameter parameter) {
            return parameter.getType() == Auditor.class;
        }

        @Override
        public Object resolve(Executable handler, Parameter parameter) {
            return auditor;
        }
    }

    /** An in-memory resolver that counts its loads and saves; supports {@link Counter} only if told to. */
    private static final class CountingResolver implements AggregateResolver {

        private final boolean supportsCounter;
        private final Map<String, Counter> counters = new HashMap<>();
        private int loads;
        private int saves;

        private CountingResolver(boolean supportsCounter) {
            this.supportsCounter = supportsCounter;
        }

        @Override
        public boolean supports(Class<?> aggregateType) {
            return supportsCounter && aggregateType == Counter.class;
        }

        @Override
        public Optional<Object> load(Class<?> aggregateType, Object aggregateId) {
            loads++;
            return Optional.ofNullable(counters.get(String.valueOf(aggregateId)));
        }

        @Override
        public void save(Object aggregate) {
            saves++;
            Counter counter = (Counter) aggregate;
            counters.put(counter.counterId, counter);
        }
    }

    /** Command for {@link IdentityService}. */
    @Command
    record Stamp(String subject) {
    }

    /** A service handler that records the identity of each command it handles; fails if told to. */
    public static final class IdentityService {

        private final List<CommandId> seen = new ArrayList<>();
        private boolean failNext;

        @CommandHandler
        public void handle(Stamp command, CommandId commandId) {
            seen.add(commandId);
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("stamp failed");
            }
        }
    }

    /** A bound engine unit of work that records events and failure reports. */
    private static final class RecordingUnitOfWork implements UnitOfWork {

        private final List<Object> recorded = new ArrayList<>();
        private final List<Throwable> failures = new ArrayList<>();
        private final List<CommandId> handled = new ArrayList<>();
        private final java.util.Set<CommandId> committed = new java.util.HashSet<>();
        private int stamps;

        @Override
        public CommandId nextCommandId(Class<?> commandType) {
            return CommandId.of("saga-1", "step", commandType.getName(), ++stamps);
        }

        @Override
        public boolean alreadyHandled(CommandId id) {
            return committed.contains(id);
        }

        @Override
        public void commandHandled(CommandId id) {
            handled.add(id);
        }

        private final List<io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand> logged = new ArrayList<>();

        @Override
        public void recordLoggedCommand(io.github.gkosharovdev.sagaforge.core.spi.OutgoingCommand command) {
            logged.add(command);
        }

        @Override
        public void record(Object domainEvent) {
            recorded.add(domainEvent);
        }

        @Override
        public void failed(Throwable failure) {
            failures.add(failure);
        }
    }

    /** How a send inside a bound unit of work ended. */
    private record Sent(Throwable thrown, boolean rollbackOnlyWhenThrown) {
    }

    private final InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
    private final PaymentService payments = new PaymentService();
    private final InventoryRepository inventories = new InventoryRepository();
    private final BookingRepository bookings = new BookingRepository();
    private final AuditService audits = new AuditService();
    private final IdentityService identities = new IdentityService();
    private final Auditor auditor = new Auditor();
    private final Auditor laterAuditor = new Auditor();
    private final RecordingUnitOfWork unitOfWork = new RecordingUnitOfWork();
    private final CountingResolver unsupporting = new CountingResolver(false);
    private final CountingResolver firstSupporting = new CountingResolver(true);
    private final CountingResolver secondSupporting = new CountingResolver(true);
    private EngineCommandGateway gateway;

    private EngineCommandGateway gateway(List<HandlerParameterResolver> parameterResolvers) {
        List<Class<?>> registered = List.of(PaymentService.class, Inventory.class, InventoryRepository.class,
                Booking.class, BookingRepository.class, Counter.class, AuditService.class, IdentityService.class);
        MapHandlerInstanceProvider instances = new MapHandlerInstanceProvider()
                .register(payments).register(inventories).register(bookings).register(audits).register(identities);
        List<AggregateResolver> resolvers = List.of(unsupporting, firstSupporting, secondSupporting);
        Outbox outbox = CapturingOutbox.afterCommit();
        EngineSettings settings = new EngineSettings(new TransactionalInMemoryStorageProvider(boundary),
                new InMemorySagaContextStore(), boundary, new RecordingEventDispatcher(), instances, resolvers,
                parameterResolvers, Optional.empty(), outbox, outbox.timing(),
                new TimeAndIdentity(Clock.fixed(NOW, ZoneOffset.UTC), () -> "saga-1", () -> "uow-1"));
        return EngineCommandGateway.of(registered, AggregateAccess.of(registered, instances, resolvers), settings,
                new Diagnostics(System.getLogger(EngineCommandGatewayTest.class.getName())));
    }

    @BeforeEach
    void setUp() {
        Booking.resetFaults();
        gateway = gateway(List.of(new AuditorResolver(auditor), new AuditorResolver(laterAuditor)));
    }

    /** Sends {@code command} through {@code via} inside {@link #unitOfWork}, bound in a fresh transaction. */
    private Sent sendInUnitOfWork(EngineCommandGateway via, Object command) {
        LocalTransaction transaction = boundary.begin();
        Throwable[] thrown = new Throwable[1];
        boolean[] rollbackOnly = new boolean[1];
        try {
            DomainEventScope.runWith(unitOfWork, () -> {
                try {
                    via.send(command);
                } catch (RuntimeException | Error failure) {
                    thrown[0] = failure;
                    rollbackOnly[0] = transaction.isRollbackOnly();
                }
            });
        } finally {
            transaction.rollback();
        }
        return new Sent(thrown[0], rollbackOnly[0]);
    }

    private Sent sendInUnitOfWork(Object command) {
        return sendInUnitOfWork(gateway, command);
    }

    /** The failure left send unchanged, after marking the transaction rollback-only and reporting itself. */
    private void assertFailedUnitOfWork(Sent sent) {
        assertThat(sent.thrown()).isNotNull();
        assertThat(sent.rollbackOnlyWhenThrown()).isTrue();
        assertThat(unitOfWork.failures).containsExactly(sent.thrown());
    }

    @Nested
    @DisplayName("routing")
    class Routing {

        @Test
        @DisplayName("a service handler runs on the instance from the HandlerInstanceProvider")
        void serviceHandler() {
            Sent sent = sendInUnitOfWork(new InitiatePayment("booking-1", 300L));

            assertThat(sent.thrown()).isNull();
            assertThat(payments.find(PaymentService.paymentIdFor("booking-1"))).isPresent();
            assertThat(unitOfWork.recorded).singleElement().isInstanceOf(PaymentInitiatedEvent.class);
            assertThat(unitOfWork.failures).isEmpty();
        }

        @Test
        @DisplayName("an aggregate instance handler loads its target through the client loader and saves it")
        void aggregateInstanceHandler() {
            inventories.add(new Inventory("inventory-1"));

            Sent sent = sendInUnitOfWork(new ClaimAvailability("inventory-1", "booking-1", CHECK_IN, CHECK_OUT));

            assertThat(sent.thrown()).isNull();
            assertThat(inventories.load("inventory-1").claim(Inventory.claimIdFor("booking-1"))).isPresent();
            assertThat(inventories.saveCount()).isEqualTo(1);
            assertThat(unitOfWork.recorded).singleElement().isInstanceOf(AvailabilityClaimedEvent.class);
        }

        @Test
        @DisplayName("a creation handler's new aggregate is saved through the client saver")
        void creationHandler() {
            Sent sent = sendInUnitOfWork(new ConfirmBooking("booking-1", "claim-1", "payment-1"));

            assertThat(sent.thrown()).isNull();
            assertThat(bookings.load("booking-1")).isNotNull()
                    .satisfies(booking -> assertThat(booking.claimId()).isEqualTo("claim-1"));
            assertThat(bookings.saveCount()).isEqualTo(1);
            assertThat(unitOfWork.recorded).singleElement().isInstanceOf(BookingConfirmedEvent.class);
        }

        @Test
        @DisplayName("a command with no handler fails with NoCommandHandlerException")
        void noHandler() {
            Sent sent = sendInUnitOfWork(new Unrouted("x"));

            assertThat(sent.thrown()).isInstanceOf(NoCommandHandlerException.class);
            assertFailedUnitOfWork(sent);
        }

        @Test
        @DisplayName("a missing instance-method target fails with AggregateNotFoundException")
        void aggregateNotFound() {
            Sent sent = sendInUnitOfWork(new ClaimAvailability("missing", "booking-1", CHECK_IN, CHECK_OUT));

            assertThat(sent.thrown()).isInstanceOf(AggregateNotFoundException.class);
            assertThat(inventories.saveCount()).isZero();
            assertFailedUnitOfWork(sent);
        }

        @Test
        @DisplayName("null and non-@Command commands fail with IllegalArgumentException")
        void invalidCommands() {
            Sent nullCommand = sendInUnitOfWork(null);
            assertThat(nullCommand.thrown()).isInstanceOf(IllegalArgumentException.class);
            assertThat(nullCommand.rollbackOnlyWhenThrown()).isTrue();

            Sent notACommand = sendInUnitOfWork(new NotACommand("x"));
            assertThat(notACommand.thrown()).isInstanceOf(IllegalArgumentException.class);
            assertThat(notACommand.rollbackOnlyWhenThrown()).isTrue();
            assertThat(unitOfWork.failures).containsExactly(nullCommand.thrown(), notACommand.thrown());
        }
    }

    @Nested
    @DisplayName("aggregate resolvers")
    class Resolvers {

        @Test
        @DisplayName("the first supporting resolver serves both load and save; the others are never asked")
        void firstSupportingWins() {
            firstSupporting.counters.put("counter-1", new Counter("counter-1"));

            Sent sent = sendInUnitOfWork(new Increment("counter-1"));

            assertThat(sent.thrown()).isNull();
            assertThat(firstSupporting.counters.get("counter-1").value).isEqualTo(1);
            assertThat(firstSupporting.loads).isEqualTo(1);
            assertThat(firstSupporting.saves).isEqualTo(1);
            assertThat(unsupporting.loads + unsupporting.saves).isZero();
            assertThat(secondSupporting.loads + secondSupporting.saves).isZero();
        }

        @Test
        @DisplayName("a target the supporting resolver does not find fails with AggregateNotFoundException")
        void resolverFindsNothing() {
            secondSupporting.counters.put("counter-1", new Counter("counter-1"));

            Sent sent = sendInUnitOfWork(new Increment("counter-1"));

            assertThat(sent.thrown()).isInstanceOf(AggregateNotFoundException.class);
            assertThat(secondSupporting.loads).isZero();
            assertFailedUnitOfWork(sent);
        }
    }

    @Nested
    @DisplayName("handler parameters")
    class Parameters {

        @Test
        @DisplayName("a non-command parameter is supplied by the first supporting HandlerParameterResolver")
        void resolvedByFirstSupporting() {
            Sent sent = sendInUnitOfWork(new Audit("invoice-7"));

            assertThat(sent.thrown()).isNull();
            assertThat(auditor.audited).containsExactly("invoice-7");
            assertThat(laterAuditor.audited).isEmpty();
        }

        @Test
        @DisplayName("a parameter no resolver supports fails with IllegalStateException naming handler and parameter")
        void unresolvableParameter() {
            EngineCommandGateway withoutResolvers = gateway(List.of());

            Sent sent = sendInUnitOfWork(withoutResolvers, new Audit("invoice-7"));

            assertThat(sent.thrown()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AuditService.handle")
                    .hasMessageContaining("Auditor");
            assertFailedUnitOfWork(sent);
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("a handler failure marks the transaction rollback-only before it leaves send, unchanged")
        void handlerFailure() {
            IllegalStateException failure = new IllegalStateException("payment declined by test");
            payments.failNext(failure);

            Sent sent = sendInUnitOfWork(new InitiatePayment("booking-1", 300L));

            assertThat(sent.thrown()).isSameAs(failure);
            assertFailedUnitOfWork(sent);
            assertThat(unitOfWork.recorded).isEmpty();
        }

        @Test
        @DisplayName("a creation handler failure saves nothing and is reported")
        void creationFailure() {
            IllegalStateException failure = new IllegalStateException("creation failed by test");
            Booking.failNext(failure);

            Sent sent = sendInUnitOfWork(new ConfirmBooking("booking-1", "claim-1", "payment-1"));

            assertThat(sent.thrown()).isSameAs(failure);
            assertThat(bookings.saveCount()).isZero();
            assertFailedUnitOfWork(sent);
        }
    }

    @Nested
    @DisplayName("command identity (specs/005-step-retry tasks.md T025)")
    class Identity {

        @Test
        @DisplayName("the handler receives the identity the unit of work assigned, which is then recorded as handled")
        void assignedAndRecorded() {
            Sent sent = sendInUnitOfWork(new Stamp("a"));

            assertThat(sent.thrown()).isNull();
            CommandId expected = CommandId.of("saga-1", "step", Stamp.class.getName(), 1);
            assertThat(identities.seen).containsExactly(expected);
            assertThat(unitOfWork.handled).containsExactly(expected);
        }

        @Test
        @DisplayName("a command whose identity already committed is not handled again and send returns normally")
        void alreadyHandledIsSkipped() {
            unitOfWork.committed.add(CommandId.of("saga-1", "step", Stamp.class.getName(), 1));

            Sent sent = sendInUnitOfWork(new Stamp("a"));

            assertThat(sent.thrown()).isNull();
            assertThat(identities.seen).isEmpty();
            assertThat(unitOfWork.handled).isEmpty();
            assertThat(unitOfWork.failures).isEmpty();
        }

        @Test
        @DisplayName("a handler failure leaves the identity unrecorded")
        void failureLeavesItUnrecorded() {
            identities.failNext = true;

            Sent sent = sendInUnitOfWork(new Stamp("a"));

            assertFailedUnitOfWork(sent);
            assertThat(identities.seen).hasSize(1);
            assertThat(unitOfWork.handled).isEmpty();
        }

        @Test
        @DisplayName("outside a unit of work the handler receives an unscoped identity")
        void outsideAUnitOfWork() {
            gateway.send(new Stamp("a"));

            assertThat(identities.seen).singleElement().satisfies(id -> assertThat(id.sagaScoped()).isFalse());
        }
    }
}
