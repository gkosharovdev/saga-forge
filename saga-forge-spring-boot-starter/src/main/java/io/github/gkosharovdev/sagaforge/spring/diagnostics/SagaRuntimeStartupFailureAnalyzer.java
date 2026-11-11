package io.github.gkosharovdev.sagaforge.spring.diagnostics;

import java.util.stream.Collectors;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import io.github.gkosharovdev.sagaforge.runtime.SagaRuntimeStartupException;

/** Lists every error of a refused saga model when the application fails to start (FR-017). */
public class SagaRuntimeStartupFailureAnalyzer extends AbstractFailureAnalyzer<SagaRuntimeStartupException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, SagaRuntimeStartupException cause) {
        String errors = cause.report().allErrors().stream().map(error -> "    - " + error)
                .collect(Collectors.joining(System.lineSeparator()));
        return new FailureAnalysis("The saga model was refused at startup:" + System.lineSeparator() + errors,
                "Fix each error listed above in the saga, handler or aggregate classes it names. The rules are listed in"
                        + " the Saga Forge documentation (validation rules R1 to R23).",
                cause);
    }
}
