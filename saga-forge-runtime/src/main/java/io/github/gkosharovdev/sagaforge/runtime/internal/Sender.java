package io.github.gkosharovdev.sagaforge.runtime.internal;

/**
 * The method that sent an outbox command, resolved from {@code CommandId.stepName()} against the saga model
 * (specs/012-outbox-command-failure-feedback contracts/runtime-reaction.md "Sender resolution"): a step
 * ({@link StepSender}), a compensation ({@link CompensationSender}) or neither ({@link Unknown}, for example after the
 * method was renamed).
 *
 * <p>Immutable and thread-safe.</p>
 */
sealed interface Sender {

    /** A {@code @SagaStep} method sent the command. */
    record StepSender(String stepName) implements Sender {
    }

    /** The {@code @SagaCompensation} method {@code compensationName}, which undoes {@code forStep}, sent it. */
    record CompensationSender(String compensationName, String forStep) implements Sender {
    }

    /** No step or compensation of the saga is named {@code methodName}. */
    record Unknown(String methodName) implements Sender {
    }

    /** The sender named {@code methodName} in {@code saga}; a step name wins over a compensation of the same name. */
    static Sender resolve(SagaModel saga, String methodName) {
        if (saga.hasStep(methodName)) {
            return new StepSender(methodName);
        }
        return saga.compensationNamed(methodName)
                .<Sender>map(compensation -> new CompensationSender(compensation.name(), compensation.forStep()))
                .orElseGet(() -> new Unknown(methodName));
    }
}
