package io.github.gkosharovdev.sagaforge.spring.events;

/**
 * How domain events recorded in saga units of work reach Spring's application-event listeners
 * ({@code saga-forge.application-events.mode}, {@code specs/008-spring-boot-starter} FR-018a).
 */
public enum ApplicationEventsMode {

    /**
     * Published inside the unit of work's transaction: {@code @TransactionalEventListener}s and Spring Modulith's
     * {@code @ApplicationModuleListener}s run after commit, never on rollback. The default.
     */
    IN_TRANSACTION,

    /** Published after the transport delivered the event, outside any transaction (007's bridge). */
    AFTER_DELIVERY,

    /** Not published to Spring at all. */
    NONE
}
