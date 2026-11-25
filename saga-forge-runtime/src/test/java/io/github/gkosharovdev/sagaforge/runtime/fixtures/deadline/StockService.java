package io.github.gkosharovdev.sagaforge.runtime.fixtures.deadline;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every command {@link PaymentWindowSaga} sends, in order. */
public final class StockService {

    private final List<Object> handled = new CopyOnWriteArrayList<>();
    private volatile RuntimeException remindFailure;

    /** Makes every later {@link RemindCustomer} throw {@code failure}. */
    public void failReminders(RuntimeException failure) {
        this.remindFailure = failure;
    }

    @CommandHandler
    public void reserve(ReserveStock command) {
        handled.add(command);
    }

    @CommandHandler
    public void release(ReleaseStock command) {
        handled.add(command);
    }

    @CommandHandler
    public void remind(RemindCustomer command) {
        if (remindFailure != null) {
            throw remindFailure;
        }
        handled.add(command);
    }

    public List<Object> handled() {
        return List.copyOf(handled);
    }
}
