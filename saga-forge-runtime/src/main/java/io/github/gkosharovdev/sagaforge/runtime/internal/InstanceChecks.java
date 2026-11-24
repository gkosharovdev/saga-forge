package io.github.gkosharovdev.sagaforge.runtime.internal;

import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.StepStatus;
import java.util.Optional;

/**
 * Pure checks over a locked saga instance, shared by the {@linkplain UnitOfWork.Work#admit(Optional) admissions} of
 * several works. They read the instance and never write.
 *
 * <p>Stateless and thread-safe.</p>
 */
final class InstanceChecks {

    private InstanceChecks() {
    }

    /** {@code true} if there is no instance, or it has reached a terminal status. */
    static boolean missingOrTerminal(Optional<SagaInstance> locked) {
        return locked.map(instance -> EngineSagaLifecycle.isTerminal(instance.status())).orElse(true);
    }

    /** {@code true} if at least one step of {@code instance} completed, so a unit of work of it committed. */
    static boolean anyStepCompleted(SagaInstance instance) {
        return instance.steps().values().stream().anyMatch(step -> step.status() == StepStatus.COMPLETED);
    }
}
