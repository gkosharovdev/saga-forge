package io.github.gkosharovdev.sagaforge.core.execution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Booking;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.BookingRequestedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.CreateBookingSaga;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.Inventory;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.InventoryRepository;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentConfirmedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentDeclinedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentInitiatedEvent;
import io.github.gkosharovdev.sagaforge.core.execution.fixtures.booking.PaymentService;
import io.github.gkosharovdev.sagaforge.core.execution.harness.SagaTestHarness;
import io.github.gkosharovdev.sagaforge.core.execution.harness.UnitOfWorkResult;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for the long-running {@link CreateBookingSaga} (spec 002 US3, SC-003, SC-004), exercised
 * through the test-only {@link SagaTestHarness}.
 *
 * <p>Each run delivers <em>booking requested</em> (first unit of work, ends at the wait point), then the payment
 * provider's answer (second unit of work), optionally with a failure injected into the second unit of work's
 * command handler: {@link Booking#failNext()} on the confirmed path, the loaded {@link Inventory}'s
 * {@code failNext()} on the declined path. The second unit of work runs either in the same harness or in a fresh
 * one built from {@link SagaTestHarness#persistedState()}; the client repositories and payment service (the
 * "database") are registered again in the fresh harness, as in {@code HarnessResumeTest}.</p>
 *
 * <p>Every try builds fresh fixtures and resets {@link Booking}'s thread-local fault switch, because jqwik does
 * not reliably run {@code @BeforeEach} per try.</p>
 */
@Tag("Feature_002-transactional-step-sequence")
class LongRunningSagaPropertyTest {

    /** The payment provider's answer delivered to the waiting saga. */
    enum PaymentAnswer {
        CONFIRMED,
        DECLINED
    }

    /** Where the second unit of work runs. */
    enum Resume {
        /** The harness that ran the first unit of work. */
        SAME_HARNESS,
        /** A fresh harness built from the first harness' {@link SagaTestHarness#persistedState()}. */
        FRESH_HARNESS
    }

    /**
     * One generated run.
     *
     * @param bookingId   correlation value of every step
     * @param inventoryId the inventory the booking claims
     * @param amount      the amount to charge
     * @param answer      the payment provider's answer
     * @param failSecond  whether the second unit of work's command handler fails
     */
    record Scenario(String bookingId, String inventoryId, long amount, PaymentAnswer answer, boolean failSecond) {

        String paymentId() {
            return PaymentService.paymentIdFor(bookingId);
        }

        String claimId() {
            return Inventory.claimIdFor(bookingId);
        }

        BookingRequestedEvent bookingRequested() {
            return new BookingRequestedEvent(
                    bookingId, inventoryId, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 4), amount);
        }

        Object answerEvent() {
            return answer == PaymentAnswer.CONFIRMED
                    ? new PaymentConfirmedEvent(paymentId(), bookingId)
                    : new PaymentDeclinedEvent(paymentId(), bookingId, "declined by provider");
        }

        /** Steps the second unit of work runs on the chosen path. */
        List<String> takenPath() {
            return answer == PaymentAnswer.CONFIRMED
                    ? CreateBookingSaga.CONFIRMED_PATH_STEPS
                    : CreateBookingSaga.DECLINED_PATH_STEPS;
        }

        /** Steps of the path not taken. */
        List<String> untakenPath() {
            return answer == PaymentAnswer.CONFIRMED
                    ? CreateBookingSaga.DECLINED_PATH_STEPS
                    : CreateBookingSaga.CONFIRMED_PATH_STEPS;
        }

        String expectedOutcome() {
            return answer == PaymentAnswer.CONFIRMED
                    ? CreateBookingSaga.OUTCOME_CONFIRMED
                    : CreateBookingSaga.OUTCOME_DECLINED;
        }

        /** The step whose dispatched command fails when {@link #failSecond()} is set. */
        String failingStep() {
            return answer == PaymentAnswer.CONFIRMED
                    ? CreateBookingSaga.PAYMENT_CONFIRMED_STEP
                    : CreateBookingSaga.PAYMENT_DECLINED_STEP;
        }
    }

    /** What a finished run left behind, for comparing runs. */
    record RunSnapshot(
            UnitOfWorkResult.Outcome secondOutcome,
            List<String> secondStepsRun,
            SagaInstance instance,
            Map<String, Object> context,
            boolean claimHeld,
            boolean paymentExists,
            boolean bookingExists) {
    }

    /** The client-side "database": survives a harness restart. */
    private record Fixture(PaymentService payments, InventoryRepository inventories, BookingRepository bookings) {

        static Fixture fresh(Scenario scenario) {
            Booking.resetFaults();
            InventoryRepository inventories = new InventoryRepository();
            inventories.add(new Inventory(scenario.inventoryId()));
            return new Fixture(new PaymentService(), inventories, new BookingRepository());
        }

        SagaTestHarness harness(SagaTestHarness.Builder builder) {
            return builder
                    .saga(CreateBookingSaga.class)
                    .handler(payments)
                    .aggregate(Inventory.class)
                    .repository(inventories)
                    .aggregate(Booking.class)
                    .repository(bookings)
                    .build();
        }

        void armSecondUnitFailure(Scenario scenario) {
            if (scenario.answer() == PaymentAnswer.CONFIRMED) {
                Booking.failNext();
            } else {
                inventories.load(scenario.inventoryId()).failNext();
            }
        }
    }

    /** A completed two-unit run: the first unit's result, the second unit's result, and the final harness. */
    private record Run(
            String sagaId,
            UnitOfWorkResult first,
            SagaInstance afterFirst,
            UnitOfWorkResult second,
            SagaTestHarness finalHarness,
            Fixture fixture) {

        RunSnapshot snapshot(Scenario scenario) {
            Inventory inventory = fixture.inventories().load(scenario.inventoryId());
            return new RunSnapshot(
                    second.outcome(),
                    second.stepsRun(),
                    finalHarness.instance(sagaId),
                    finalHarness.context(sagaId),
                    inventory.claim(scenario.claimId()).isPresent(),
                    fixture.payments().find(scenario.paymentId()).isPresent(),
                    fixture.bookings().load(scenario.bookingId()) != null);
        }
    }

    private static Run run(Scenario scenario, Resume resume) {
        Fixture fixture = Fixture.fresh(scenario);
        SagaTestHarness original = fixture.harness(SagaTestHarness.builder());
        UnitOfWorkResult first = original.deliver(scenario.bookingRequested());
        SagaInstance afterFirst = first.sagaId() == null ? null : original.instance(first.sagaId());

        SagaTestHarness second = resume == Resume.SAME_HARNESS
                ? original
                : fixture.harness(SagaTestHarness.fromPersistedState(original.persistedState()));
        if (scenario.failSecond()) {
            fixture.armSecondUnitFailure(scenario);
        }
        UnitOfWorkResult secondResult = second.deliver(scenario.answerEvent());
        return new Run(first.sagaId(), first, afterFirst, secondResult, second, fixture);
    }

    // ── Property A: wait point, then exactly one outcome or FAILED, context always readable ──

    /**
     * Property A: For any booking id, inventory id and amount, any payment answer (confirmed or declined), with
     * or without a failure injected into the second unit of work, and whether the second unit of work runs in the
     * same harness or in a fresh one built from persisted state:
     * <ul>
     *   <li>the first unit of work commits at the wait point: {@code RUNNING}, the first-unit steps
     *       {@code COMPLETED}, every other step {@code NOT_RUN}, and only <em>payment initiated</em>
     *       dispatched;</li>
     *   <li>the second unit of work ends the saga either {@code COMPLETED} with the outcome of the chosen path
     *       (taken path {@code COMPLETED}, untaken path {@code NOT_RUN}) or {@code FAILED} (never both); on
     *       {@code FAILED} the failure names the failing step, the first unit's effects remain (claim held,
     *       payment exists, no booking) and the second unit's steps stay {@code NOT_RUN};</li>
     *   <li>every {@code SagaContext} value written in either unit of work stays readable, including the payment
     *       id written by a rolled-back confirmed-path unit of work.</li>
     * </ul>
     *
     * <p><b>Validates: Requirements FR-004, FR-005, FR-020, FR-021, FR-024, SC-003, SC-004</b></p>
     */
    @Property(tries = 200)
    @Tag("Property_A_LongRunning_exactly_one_outcome_or_failed_and_context_survives")
    void secondUnitReachesExactlyOneOutcomeOrFailsAndContextSurvives(
            @ForAll("scenarios") Scenario scenario,
            @ForAll Resume resume) {
        try {
            Run run = run(scenario, resume);

            // First unit of work: wait point.
            assertThat(run.first().committed()).as("first unit committed").isTrue();
            assertThat(run.first().stepsRun()).containsExactlyElementsOf(CreateBookingSaga.FIRST_UNIT_STEPS);
            assertThat(run.first().dispatched()).containsExactly(
                    new PaymentInitiatedEvent(scenario.paymentId(), scenario.bookingId(), scenario.amount()));
            SagaInstance waiting = run.afterFirst();
            assertThat(waiting.status()).isEqualTo(SagaStatus.RUNNING);
            assertThat(waiting.outcome()).isNull();
            assertThat(waiting.failure()).isNull();
            assertThat(waiting.steps()).containsOnlyKeys(CreateBookingSaga.ALL_STEPS);
            for (String step : CreateBookingSaga.ALL_STEPS) {
                StepStatus expected = CreateBookingSaga.FIRST_UNIT_STEPS.contains(step)
                        ? StepStatus.COMPLETED
                        : StepStatus.NOT_RUN;
                assertThat(waiting.steps().get(step).status()).as("after first unit: %s", step).isEqualTo(expected);
            }

            // Second unit of work: exactly one of {COMPLETED with the chosen outcome, FAILED}.
            UnitOfWorkResult second = run.second();
            assertThat(second.sagaId()).isEqualTo(run.sagaId());
            SagaInstance done = run.finalHarness().instance(run.sagaId());
            assertThat(done.status()).isIn(SagaStatus.COMPLETED, SagaStatus.FAILED);
            assertThat(done.steps()).containsOnlyKeys(CreateBookingSaga.ALL_STEPS);
            for (String step : CreateBookingSaga.FIRST_UNIT_STEPS) {
                assertThat(done.steps().get(step).status()).as("first-unit step %s", step)
                        .isEqualTo(StepStatus.COMPLETED);
            }
            for (String step : scenario.untakenPath()) {
                assertThat(done.steps().get(step).status()).as("untaken step %s", step)
                        .isEqualTo(StepStatus.NOT_RUN);
            }

            Inventory inventory = run.fixture().inventories().load(scenario.inventoryId());
            assertThat(run.fixture().payments().find(scenario.paymentId())).as("payment exists").isPresent();

            if (scenario.failSecond()) {
                assertThat(second.rolledBack()).as("second unit rolled back").isTrue();
                assertThat(done.status()).isEqualTo(SagaStatus.FAILED);
                assertThat(done.outcome()).isNull();
                assertThat(done.failure()).isNotNull();
                assertThat(done.failure().stepName()).isEqualTo(scenario.failingStep());
                assertThat(second.failure()).isEqualTo(done.failure());
                assertThat(second.stepsRun()).last().isEqualTo(scenario.failingStep());
                assertThat(second.dispatched()).isEmpty();
                for (String step : scenario.takenPath()) {
                    assertThat(done.steps().get(step).status()).as("rolled-back step %s", step)
                            .isEqualTo(StepStatus.NOT_RUN);
                }
                // The first unit of work's effects remain; the second's are rolled back.
                assertThat(inventory.claim(scenario.claimId())).as("claim still held").isPresent();
                assertThat(run.fixture().bookings().load(scenario.bookingId())).as("no booking").isNull();
            } else {
                assertThat(second.committed()).as("second unit committed").isTrue();
                assertThat(second.stepsRun()).containsExactlyElementsOf(scenario.takenPath());
                assertThat(done.status()).isEqualTo(SagaStatus.COMPLETED);
                assertThat(done.failure()).isNull();
                assertThat(done.outcome()).isEqualTo(scenario.expectedOutcome());
                for (String step : scenario.takenPath()) {
                    assertThat(done.steps().get(step).status()).as("taken step %s", step)
                            .isEqualTo(StepStatus.COMPLETED);
                }
                if (scenario.answer() == PaymentAnswer.CONFIRMED) {
                    Booking booking = run.fixture().bookings().load(scenario.bookingId());
                    assertThat(booking).as("booking created").isNotNull();
                    assertThat(booking.claimId()).isEqualTo(scenario.claimId());
                    assertThat(booking.paymentId()).isEqualTo(scenario.paymentId());
                    assertThat(inventory.claim(scenario.claimId())).as("claim held by booking").isPresent();
                } else {
                    assertThat(inventory.claim(scenario.claimId())).as("claim released").isEmpty();
                    assertThat(run.fixture().bookings().load(scenario.bookingId())).as("no booking").isNull();
                }
            }

            // Context written in every unit of work stays readable, committed or rolled back (FR-024).
            Map<String, Object> context = run.finalHarness().context(run.sagaId());
            assertThat(context)
                    .containsEntry(CreateBookingSaga.CONTEXT_INVENTORY_ID, scenario.inventoryId())
                    .containsEntry(CreateBookingSaga.CONTEXT_AMOUNT, scenario.amount())
                    .containsEntry(CreateBookingSaga.CONTEXT_CLAIM_ID, scenario.claimId());
            if (scenario.answer() == PaymentAnswer.CONFIRMED) {
                assertThat(context).containsEntry(CreateBookingSaga.CONTEXT_PAYMENT_ID, scenario.paymentId());
            } else {
                assertThat(context).doesNotContainKey(CreateBookingSaga.CONTEXT_PAYMENT_ID);
            }
        } finally {
            Booking.resetFaults();
        }
    }

    // ── Property B: resuming from persisted state ends like an uninterrupted run ──

    /**
     * Property B: For any scenario, running the second unit of work in a fresh harness built from
     * {@link SagaTestHarness#persistedState()} ends exactly like running it in the original harness: same
     * second-unit outcome and steps run, same instance status, outcome, failure and step records, same
     * {@code SagaContext} values, and the same client-side effects (claim, payment, booking). Each run uses its
     * own independent fixtures.
     *
     * <p><b>Validates: Requirements FR-020, SC-003</b></p>
     */
    @Property(tries = 100)
    @Tag("Property_B_LongRunning_resume_from_persisted_state_matches_uninterrupted_run")
    void resumeFromPersistedStateEndsLikeUninterruptedRun(@ForAll("scenarios") Scenario scenario) {
        try {
            RunSnapshot uninterrupted = run(scenario, Resume.SAME_HARNESS).snapshot(scenario);
            Booking.resetFaults();
            RunSnapshot resumed = run(scenario, Resume.FRESH_HARNESS).snapshot(scenario);

            assertThat(resumed.secondOutcome()).isEqualTo(uninterrupted.secondOutcome());
            assertThat(resumed.secondStepsRun()).isEqualTo(uninterrupted.secondStepsRun());
            assertThat(resumed.instance().status()).isEqualTo(uninterrupted.instance().status());
            assertThat(resumed.instance().outcome()).isEqualTo(uninterrupted.instance().outcome());
            assertThat(resumed.instance().failure()).isEqualTo(uninterrupted.instance().failure());
            assertThat(resumed.instance().steps()).isEqualTo(uninterrupted.instance().steps());
            assertThat(resumed.context()).isEqualTo(uninterrupted.context());
            assertThat(resumed.claimHeld()).isEqualTo(uninterrupted.claimHeld());
            assertThat(resumed.paymentExists()).isEqualTo(uninterrupted.paymentExists());
            assertThat(resumed.bookingExists()).isEqualTo(uninterrupted.bookingExists());
        } finally {
            Booking.resetFaults();
        }
    }

    // ── Arbitraries ─────────────────────────────────────────────────────────

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(
                        ids(), ids(), Arbitraries.longs(),
                        Arbitraries.of(PaymentAnswer.class), Arbitraries.of(true, false))
                .as(Scenario::new);
    }

    private static Arbitrary<String> ids() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> !s.isBlank());
    }
}
