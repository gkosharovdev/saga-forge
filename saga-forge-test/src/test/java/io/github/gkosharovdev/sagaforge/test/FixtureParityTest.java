package io.github.gkosharovdev.sagaforge.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.execution.harness.InMemorySagaContextStore;
import io.github.gkosharovdev.sagaforge.core.execution.harness.MapHandlerInstanceProvider;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInterceptor;
import io.github.gkosharovdev.sagaforge.core.spi.HandlingOutcome;
import io.github.gkosharovdev.sagaforge.core.spi.SagaHandlingResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.support.InMemoryTransactionBoundary;
import io.github.gkosharovdev.sagaforge.runtime.support.TestTransport;
import io.github.gkosharovdev.sagaforge.runtime.support.TransactionalInMemoryStorageProvider;
import io.github.gkosharovdev.sagaforge.test.fixtures.AuditSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.CardDeclined;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.GatewayTimeout;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderPlaced;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.OrderViewed;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.PaymentTaken;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.RefundPayment;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ReserveStock;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.ShopSaga;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockMissing;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.StockReserved;
import io.github.gkosharovdev.sagaforge.test.fixtures.Shop.TakePayment;

/**
 * FR-017 and SC-003: every scenario of User Stories 1 to 3 gives the same handling outcomes, stored instances and
 * committed commands through the fixture as through a {@link SagaRuntime} assembled by hand over the runtime's own test
 * doubles, with the same clock, identities and command failures (tasks.md T058).
 */
class FixtureParityTest {

    /** A command failure both runs apply: {@code times} failures of {@code type}, or always if negative. */
    private record Failure(Class<?> type, Supplier<? extends RuntimeException> exception, int times) {
    }

    private record Handled(String sagaType, String sagaId, HandlingOutcome outcome, boolean started,
            String outcomeStep) {

        static Handled of(SagaHandlingResult result) {
            return new Handled(result.sagaType(), result.sagaId(), result.outcome(), result.started(),
                    result.outcomeStep());
        }
    }

    private record Sent(Object command, CommandId commandId) {
    }

    /** What one run observed. */
    private record Observed(List<List<Handled>> outcomes, List<Optional<SagaInstance>> instances, List<Sent> commands) {
    }

    static Stream<Arguments> scenarios() {
        OrderPlaced placed = new OrderPlaced("o-1", "10");
        PaymentTaken paid = new PaymentTaken("payment-1", "o-1", "10");
        return Stream.of(
                Arguments.of("start", List.of(placed), List.of()),
                Arguments.of("next step", List.of(placed, paid), List.of()),
                Arguments.of("end step", List.of(placed, paid, new StockReserved("o-1")), List.of()),
                Arguments.of("business failure compensates", List.of(placed, paid, new StockMissing("o-1")),
                        List.of()),
                Arguments.of("transient twice, then success", List.of(placed),
                        List.of(new Failure(TakePayment.class, GatewayTimeout::new, 2))),
                Arguments.of("retries exhausted", List.of(placed),
                        List.of(new Failure(TakePayment.class, GatewayTimeout::new, -1))),
                Arguments.of("final failure compensates", List.of(placed, paid),
                        List.of(new Failure(ReserveStock.class, CardDeclined::new, -1))),
                Arguments.of("unclassified failure", List.of(placed, new PaymentTaken("p", "o-1", "99")), List.of()),
                Arguments.of("failing compensation", List.of(placed, paid, new StockMissing("o-1")),
                        List.of(new Failure(RefundPayment.class, () -> new IllegalStateException("no"), -1))),
                Arguments.of("two instances", List.of(placed, new OrderPlaced("o-2", "20"),
                        new PaymentTaken("payment-2", "o-2", "20")), List.of()),
                Arguments.of("unmatched and ignored", List.of(new PaymentTaken("p", "o-x", "1"), new OrderViewed("o-1")),
                        List.of()),
                Arguments.of("duplicate", List.of(placed, paid, paid), List.of()),
                Arguments.of("duplicate start", List.of(placed, placed), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void fixtureMatchesAnAssembledRuntime(String name, List<Object> events, List<Failure> failures) {
        Observed fixture = throughFixture(events, failures);
        Observed assembled = throughAssembledRuntime(events, failures);

        assertThat(fixture.outcomes()).as("handling outcomes").isEqualTo(assembled.outcomes());
        assertThat(fixture.instances()).as("stored instances").isEqualTo(assembled.instances());
        assertThat(fixture.commands()).as("committed commands").isEqualTo(assembled.commands());
    }

    private static Observed throughFixture(List<Object> events, List<Failure> failures) {
        SagaFixture.Builder builder = SagaFixture.of(ShopSaga.class, AuditSaga.class);
        for (Failure failure : failures) {
            if (failure.times() < 0) {
                builder.failCommand(failure.type(), failure.exception());
            } else {
                builder.failCommand(failure.type(), failure.exception(), failure.times());
            }
        }
        SagaFixture fixture = builder.build();
        List<List<Handled>> outcomes = new ArrayList<>();
        for (Object event : events) {
            outcomes.add(fixture.when(event).result().results().stream().map(Handled::of).toList());
        }
        List<Optional<SagaInstance>> instances = sagaIds(outcomes).stream()
                .map(id -> fixture.current(id).map(InstanceView::stored)).toList();
        List<Sent> commands = fixture.commands().stream()
                .map(command -> new Sent(command.command(), command.commandId())).toList();
        return new Observed(outcomes, instances, commands);
    }

    private static Observed throughAssembledRuntime(List<Object> events, List<Failure> failures) {
        InMemoryTransactionBoundary boundary = new InMemoryTransactionBoundary();
        TransactionalInMemoryStorageProvider storage = new TransactionalInMemoryStorageProvider(boundary);
        TestTransport transport = new TestTransport();
        FixtureClock clock = new FixtureClock(FixtureClock.DEFAULT_START);
        List<Sent> committed = new ArrayList<>();
        int[] remaining = failures.stream().mapToInt(Failure::times).toArray();
        CommandInterceptor recording = invocation -> {
            for (int i = 0; i < failures.size(); i++) {
                if (failures.get(i).type().isInstance(invocation.command()) && remaining[i] != 0) {
                    remaining[i]--;
                    throw failures.get(i).exception().get();
                }
            }
            Sent sent = new Sent(invocation.command(), invocation.commandId());
            boundary.current().flatMap(tx -> tx.resource(InMemoryTransactionBoundary.Scope.class))
                    .orElseThrow().onCommit(() -> committed.add(sent));
        };
        AtomicInteger sagaIds = new AtomicInteger();
        AtomicInteger unitOfWorkIds = new AtomicInteger();
        SagaRuntime runtime = SagaRuntime.builder()
                .register(ShopSaga.class, AuditSaga.class)
                .storage(storage)
                .contextStore(new InMemorySagaContextStore())
                .transactions(boundary)
                .dispatcher(transport)
                .handlerInstances(new MapHandlerInstanceProvider())
                .clock(clock)
                .sagaIds(() -> "saga-" + sagaIds.incrementAndGet())
                .unitOfWorkIds(() -> "uow-" + unitOfWorkIds.incrementAndGet())
                .retryPause(clock::advance)
                .commandInterceptor(recording)
                .build();
        transport.connect(runtime).loopBack();
        runtime.start();
        List<List<Handled>> outcomes = new ArrayList<>();
        for (Object event : events) {
            outcomes.add(runtime.receive(event).results().stream().map(Handled::of).toList());
        }
        List<Optional<SagaInstance>> instances = sagaIds(outcomes).stream()
                .map(id -> storage.committed().findById(id)).toList();
        return new Observed(outcomes, instances, List.copyOf(committed));
    }

    private static List<String> sagaIds(List<List<Handled>> outcomes) {
        return outcomes.stream().flatMap(List::stream).map(Handled::sagaId)
                .filter(id -> id != null).distinct().toList();
    }
}
