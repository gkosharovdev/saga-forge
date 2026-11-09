package io.github.gkosharovdev.sagaforge.engine;

/**
 * Thrown by {@link SagaEngine#start()} when the registered model or plug-ins are not runnable; the engine is then
 * refused for good and accepts no event (contracts/engine-startup.md, FR-034, FR-036).
 *
 * <p>The message is a header line followed by {@link StartupReport#allErrors()}, one error per line.</p>
 */
public class SagaEngineStartupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final StartupReport report;

    /**
     * @param report every error found
     * @throws IllegalArgumentException if {@code report} is {@code null}
     */
    public SagaEngineStartupException(StartupReport report) {
        super(messageOf(report));
        this.report = report;
    }

    /** Every error found, in the order {@link StartupReport#allErrors()} lists them. */
    public StartupReport report() {
        return report;
    }

    private static String messageOf(StartupReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        StringBuilder message = new StringBuilder("Saga engine refused to start (")
                .append(report.allErrors().size()).append(" errors):");
        report.allErrors().forEach(error -> message.append(System.lineSeparator()).append(error));
        return message.toString();
    }
}
