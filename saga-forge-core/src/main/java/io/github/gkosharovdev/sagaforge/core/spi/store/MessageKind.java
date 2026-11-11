package io.github.gkosharovdev.sagaforge.core.spi.store;

/** What a logged message carries. */
public enum MessageKind {

    /** A {@code @DomainEvent}, delivered to the engine's {@code EventReceiver}. */
    EVENT,

    /** A {@code @Command(delivery = OUTBOX)} command, delivered to the engine's {@code CommandReceiver}. */
    COMMAND
}
