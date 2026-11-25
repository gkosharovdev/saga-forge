package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.spi.CommandFailure;
import io.github.gkosharovdev.sagaforge.core.spi.CompensationStatus;
import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.SagaFailure;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaStatus;
import io.github.gkosharovdev.sagaforge.core.spi.StepRecord;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Records that an outbox command a compensation sent failed for good, in a unit of work of its own
 * (specs/012-outbox-command-failure-feedback contracts/runtime-reaction.md §B, FR-012a): the compensation of
 * {@code forStep}, which committed when it sent the command, becomes {@code FAILED}, and the still compensating instance
 * ends {@code COMPENSATION_FAILED}; compensations not yet run do not run. Then it calls {@code inTransaction} with the
 * unit of work's transaction.
 *
 * <p>Admits only a {@code COMPENSATING} instance whose {@code forStep} compensation is {@code COMPENSATED}. Otherwise
 * it skips with the diagnostic reason: {@code missing}, {@code already-compensated} (the instance ended
 * {@code COMPENSATED} before the report came; G-012-01), {@code terminal:<status>} or {@code not-compensated}.</p>
 */
final class RecordCompensationCommandFailure implements UnitOfWork.Work<SagaFailure, String> {

    private final String forStep;
    private final CommandFailure report;
    private final Consumer<LocalTransaction> inTransaction;

    RecordCompensationCommandFailure(String forStep, CommandFailure report, Consumer<LocalTransaction> inTransaction) {
        this.forStep = forStep;
        this.report = report;
        this.inTransaction = inTransaction;
    }

    @Override
    public Admission<String> admit(Optional<SagaInstance> locked) {
        if (locked.isEmpty()) {
            return Admission.skip("missing");
        }
        SagaInstance instance = locked.get();
        if (instance.status() == SagaStatus.COMPENSATED) {
            return Admission.skip("already-compensated");
        }
        if (EngineSagaLifecycle.isTerminal(instance.status())) {
            return Admission.skip("terminal:" + instance.status());
        }
        StepRecord step = instance.steps().get(forStep);
        boolean compensated = step != null && step.compensation() != null
                && step.compensation().status() == CompensationStatus.COMPENSATED;
        return instance.status() == SagaStatus.COMPENSATING && compensated
                ? Admission.proceed()
                : Admission.skip("not-compensated");
    }

    @Override
    public SagaFailure perform(WorkContext context) {
        SagaFailure failure = new SagaFailure(forStep, report.causeType(), null, context.settings().sources().now(),
                report.classification(), report.attempts());
        context.lifecycle().recordCompensationCommandFailed(context.sagaId(), forStep, failure);
        inTransaction.accept(context.transaction());
        return failure;
    }
}
