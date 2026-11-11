package io.github.gkosharovdev.sagaforge.core.spi.log;

/** An appended entry's {@code messageId} is already in the log. */
public class DuplicateMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String messageId;

    /** @param messageId the duplicated identity */
    public DuplicateMessageException(String messageId) {
        super("Message " + messageId + " is already in the log");
        this.messageId = messageId;
    }

    /** @return the duplicated identity */
    public String messageId() {
        return messageId;
    }
}
