package io.github.gkosharovdev.sagaforge.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.InvoiceService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.OrderPlaced;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ReservationService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ShipmentService;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.inprocess.ThreeStepSagaProbe;
import io.github.gkosharovdev.sagaforge.core.execution.harness.SagaTestHarness;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for the saga-scoped unit of work (spec 002 US1, SC-002), exercised through the
 * test-only {@link SagaTestHarness} and the in-process {@link ThreeStepSaga} reference model.
 *
 * <p>Every try builds a fresh harness and fresh handler services, and resets the thread-local
 * {@link ThreeStepSagaProbe}, because jqwik does not reliably run {@code @BeforeEach} per try.</p>
 */
@Tag("Feature_002-transactional-step-sequence")
class SagaUnitOfWorkPropertyTest {

    /** How a failure is injected into the single unit of work of {@link ThreeStepSaga}. */
    enum InjectionKind {
        /** {@link ThreeStepSagaProbe#failStep(String)}: the step throws after doing its work. */
        STEP,
        /** A handler's {@code failNext}: the command handler throws after changing its aggregate. */
        HANDLER
    }

    /**
     * A failure injection: the kind and the step that ends up failing. For {@link InjectionKind#HANDLER}, the
     * failing step is the one whose command the failing handler handles.
     */
    record Injection(InjectionKind kind, String failingStep) {
    }

    /** Fresh collaborators for one try. */
    private record Fixture(
            SagaTestHarness harness,
            ReservationService reservations,
            InvoiceService invoices,
            ShipmentService shipments) {

        static Fixture fresh() {
            ThreeStepSagaProbe.reset();
            ReservationService reservations = new ReservationService();
            InvoiceService invoices = new InvoiceService();
            ShipmentService shipments = new ShipmentService();
            SagaTestHarness harness = SagaTestHarness.builder()
                    .saga(ThreeStepSaga.class)
                    .handler(reservations)
                    .handler(invoices)
                    .handler(shipments)
                    .build();
            return new Fixture(harness, reservations, invoices, shipments);
        }

        void arm(Injection injection, RuntimeException failure) {
            if (injection.kind() == InjectionKind.STEP) {
                ThreeStepSagaProbe.failStep(injection.failingStep(), failure);
                return;
            }
            switch (injection.failingStep()) {
                case ThreeStepSaga.START_STEP -> reservations.failNext(failure);
                case ThreeStepSaga.RESERVED_STEP -> invoices.failNext(failure);
                case ThreeStepSaga.INVOICED_STEP -> shipments.failNext(failure);
                default -> throw new IllegalArgumentException("No handler is dispatched by " + injection);
            }
        }
    }

    // ── Property A: a failure at any step position rolls back every aggregate change ──

    /**
     * Property A: For any failure position (any of the four steps, injected through the step itself or
     * through the command handler a dispatching step sends to), any order id and any amount, the single
     * unit of work rolls back: no aggregate change remains in any of the three services, the instance is
     * {@code FAILED} with the failing step and cause recorded, every step stays {@code NOT_RUN}, nothing is
     * dispatched, and the {@code SagaContext} value the start step wrote before the failure stays readable.
     *
     * <p><b>Validates: SC-002, FR-019, FR-021, FR-023, FR-024</b></p>
     */
    @Property(tries = 200)
    @Tag("Property_A_UnitOfWork_failure_rolls_back_all_aggregate_changes")
    void failureAtAnyStepRollsBackEverythingButKeepsContext(
            @ForAll("injections") Injection injection,
            @ForAll("orderIds") String orderId,
            @ForAll("amounts") long amount,
            @ForAll("quantities") int quantity) {
        Fixture fixture = Fixture.fresh();
        try {
            RuntimeException injected = new IllegalStateException("injected at " + injection);
            fixture.arm(injection, injected);

            UnitOfWorkResult result = fixture.harness().deliver(new OrderPlaced(orderId, amount, quantity));

            assertThat(result.rolledBack()).as("rolled back").isTrue();
            assertThat(result.cause()).isSameAs(injected);
            assertThat(result.sagaId()).isNotNull();

            assertThat(fixture.reservations().changedAggregates()).isEmpty();
            assertThat(fixture.invoices().changedAggregates()).isEmpty();
            assertThat(fixture.shipments().changedAggregates()).isEmpty();

            SagaInstance instance = fixture.harness().instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.FAILED);
            assertThat(instance.outcome()).isNull();
            assertThat(instance.failure()).isNotNull();
            assertThat(instance.failure().stepName()).isEqualTo(injection.failingStep());
            assertThat(instance.failure().causeType()).isEqualTo(injected.getClass().getName());
            assertThat(result.failure()).isEqualTo(instance.failure());
            assertThat(instance.steps()).containsOnlyKeys(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
            assertThat(instance.steps().values())
                    .allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.NOT_RUN));

            assertThat(result.stepsRun()).last().isEqualTo(injection.failingStep());
            assertThat(result.dispatched()).isEmpty();
            assertThat(fixture.harness().dispatcher().dispatched()).isEmpty();

            assertThat(fixture.harness().context(result.sagaId()))
                    .containsEntry(ThreeStepSaga.CONTEXT_AMOUNT, amount);
        } finally {
            ThreeStepSagaProbe.reset();
        }
    }

    // ── Property B: the happy path commits every step together ──────────────

    /**
     * Property B: For any order id and amount with no injected failure, the single unit of work commits at
     * the {@code @EndSaga} step: outcome {@link ThreeStepSaga#END_STEP}, all four steps {@code COMPLETED},
     * each service changed exactly its one aggregate for the order, and the {@code SagaContext} value
     * written by the start step is readable.
     *
     * <p><b>Validates: SC-002, FR-019, FR-023, FR-024</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_B_UnitOfWork_happy_path_commits_together")
    void happyPathCommitsAllStepsTogether(
            @ForAll("orderIds") String orderId,
            @ForAll("amounts") long amount,
            @ForAll("quantities") int quantity) {
        Fixture fixture = Fixture.fresh();
        try {
            UnitOfWorkResult result = fixture.harness().deliver(new OrderPlaced(orderId, amount, quantity));

            assertThat(result.committed()).as("committed").isTrue();
            assertThat(result.cause()).isNull();
            assertThat(result.failure()).isNull();
            assertThat(result.stepsRun()).containsExactlyElementsOf(ThreeStepSaga.STEPS_IN_EVENT_ORDER);

            SagaInstance instance = fixture.harness().instance(result.sagaId());
            assertThat(instance.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(instance.outcome()).isEqualTo(ThreeStepSaga.END_STEP);
            assertThat(instance.failure()).isNull();
            assertThat(instance.steps()).containsOnlyKeys(ThreeStepSaga.STEPS_IN_EVENT_ORDER);
            assertThat(instance.steps().values())
                    .allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.COMPLETED));

            assertThat(fixture.reservations().changedAggregates())
                    .containsExactly(ReservationService.reservationIdFor(orderId));
            assertThat(fixture.invoices().changedAggregates())
                    .containsExactly(InvoiceService.invoiceIdFor(orderId));
            assertThat(fixture.shipments().changedAggregates())
                    .containsExactly(ShipmentService.shipmentIdFor(orderId));

            assertThat(fixture.harness().context(result.sagaId()))
                    .containsEntry(ThreeStepSaga.CONTEXT_AMOUNT, amount);
        } finally {
            ThreeStepSagaProbe.reset();
        }
    }

    // ── Arbitraries ─────────────────────────────────────────────────────────

    @Provide
    Arbitrary<Injection> injections() {
        List<Injection> all = new ArrayList<>();
        for (String step : ThreeStepSaga.STEPS_IN_EVENT_ORDER) {
            all.add(new Injection(InjectionKind.STEP, step));
        }
        // The three dispatching steps; the @EndSaga step dispatches nothing, so it has no handler to fail.
        all.add(new Injection(InjectionKind.HANDLER, ThreeStepSaga.START_STEP));
        all.add(new Injection(InjectionKind.HANDLER, ThreeStepSaga.RESERVED_STEP));
        all.add(new Injection(InjectionKind.HANDLER, ThreeStepSaga.INVOICED_STEP));
        return Arbitraries.of(all);
    }

    @Provide
    Arbitrary<String> orderIds() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> !s.isBlank());
    }

    @Provide
    Arbitrary<Long> amounts() {
        return Arbitraries.longs();
    }

    @Provide
    Arbitrary<Integer> quantities() {
        return Arbitraries.integers().between(1, 1_000);
    }
}
