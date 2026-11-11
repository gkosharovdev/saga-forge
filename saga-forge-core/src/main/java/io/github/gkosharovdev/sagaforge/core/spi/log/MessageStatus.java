package io.github.gkosharovdev.sagaforge.core.spi.log;

/** Where a logged message stands ({@code specs/007-in-memory-transport} data-model.md, state machine). */
public enum MessageStatus {

    /** Waiting for delivery, being delivered under a lease, or waiting for a retry. */
    UNCONSUMED,

    /** Its consumer returned normally; removed after the retention period. */
    CONSUMED,

    /** Failed too often or could not be read; never delivered again unless an operator asks; never removed. */
    SET_ASIDE
}
