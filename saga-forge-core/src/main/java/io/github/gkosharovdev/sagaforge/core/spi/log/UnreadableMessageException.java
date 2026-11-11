package io.github.gkosharovdev.sagaforge.core.spi.log;

/**
 * A leased message whose payload cannot be decoded (its class is missing, or decoding failed). The message is
 * still leased to the caller, which sets it aside ({@code specs/007-in-memory-transport}
 * research §6).
 */
public class UnreadableMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient LoggedMessage leased;

    /**
     * @param leased the leased message, without payload; non-null
     * @param cause   why it could not be read
     */
    public UnreadableMessageException(LoggedMessage leased, Throwable cause) {
        super("Message " + leased.messageId() + " of type " + leased.payloadType() + " cannot be read: "
                + (cause == null ? "unknown" : cause.getMessage()), cause);
        this.leased = leased.withoutPayload();
    }

    /** @return the leased message, without payload */
    public LoggedMessage leased() {
        return leased;
    }
}
