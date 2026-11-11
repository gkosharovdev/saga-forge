package io.github.gkosharovdev.sagaforge.core.spi.log;

/**
 * A leaseholder write ({@code markConsumed}, {@code recordFailure}, {@code setAside}) by a consumer that no longer
 * holds the message's lease. Nothing was written; the message will be delivered again.
 */
public class LeaseLostException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String messageId;

    /**
     * @param messageId  the message
     * @param consumerId the consumer that tried to write
     */
    public LeaseLostException(String messageId, String consumerId) {
        super("Consumer " + consumerId + " no longer holds the lease of message " + messageId);
        this.messageId = messageId;
    }

    /** @return the message */
    public String messageId() {
        return messageId;
    }
}
