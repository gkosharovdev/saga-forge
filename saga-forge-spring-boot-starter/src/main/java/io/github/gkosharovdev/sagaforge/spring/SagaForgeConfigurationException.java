package io.github.gkosharovdev.sagaforge.spring;

/**
 * The starter cannot assemble a working saga runtime: no or several storage modules, a bean that is missing or
 * ambiguous, an invalid property, missing tables ({@code specs/008-spring-boot-starter} FR-007, FR-011, FR-015, FR-016).
 * Spring Boot's failure analysis shows {@link #description()} and {@link #action()} when the application fails to start.
 */
public class SagaForgeConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String description;
    private final String action;

    /**
     * @param description what is wrong, naming the class, bean, property or module; non-blank
     * @param action      how to fix it; non-blank
     */
    public SagaForgeConfigurationException(String description, String action) {
        super(description + " " + action);
        this.description = description;
        this.action = action;
    }

    /** @return what is wrong */
    public String description() {
        return description;
    }

    /** @return how to fix it */
    public String action() {
        return action;
    }
}
