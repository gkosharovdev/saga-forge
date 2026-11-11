package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for command classes. A command is dispatched through
 * {@code CommandGateway} to the single {@code @CommandHandler} for its type;
 * saga steps do not return commands.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Command {

    /**
     * How the command reaches its handler; {@link CommandDelivery#IN_UNIT_OF_WORK} unless the type opts into the
     * message store with {@link CommandDelivery#OUTBOX} ({@code specs/007-in-memory-transport} FR-010).
     *
     * @return the delivery of this command type
     */
    CommandDelivery delivery() default CommandDelivery.IN_UNIT_OF_WORK;
}
