package io.github.gkosharovdev.sagaforge.runtime.internal;

/**
 * Handles one command outside any engine unit of work, in a standalone {@link UnitOfWork} (004 FR-016, 002
 * contracts/command-gateway.md "Outside a unit of work", 007 research §3 for outbox commands).
 *
 * <p>The handler call runs with the unit of work bound as the thread's recorder; every event it records goes to the
 * {@code Outbox} as one {@code OutgoingMessages(unitOfWorkId, null, null, events)}, if there is any, at the outbox's
 * timing. On a failure (the handler's own exception, a failure reported by a nested {@code send} even if the handler
 * caught it, or a transaction found rollback-only at commit time) everything rolls back and the first reported failure,
 * else the thrown one, is rethrown unchanged. With {@code IN_TRANSACTION} an outbox failure rolls back and is rethrown;
 * with {@code AFTER_COMMIT} it is logged ({@code saga.outbox.failed}, without saga type or id) and not thrown, since
 * the handler's change is committed.</p>
 *
 * <p>Not thread-safe: confined to the calling thread.</p>
 */
final class HandleCommand implements UnitOfWork.Work<Void, Void> {

    private final Runnable handlerCall;

    private HandleCommand(Runnable handlerCall) {
        this.handlerCall = handlerCall;
    }

    /**
     * Runs {@code handlerCall} in a new standalone unit of work, then commits and hands the recorded events on.
     *
     * @throws RuntimeException the handler's failure, after rolling back
     */
    static void run(EngineSettings settings, Diagnostics diagnostics, Runnable handlerCall) {
        switch (UnitOfWork.standalone(settings, diagnostics).run(new HandleCommand(handlerCall))) {
            case UnitOfWork.Committed<Void, Void> committed -> committed.release();
            case UnitOfWork.Failed<Void, Void> failed -> throw UnitOfWork.propagate(failed.cause());
            case UnitOfWork.Conflicted<Void, Void> conflicted -> throw conflicted.conflict();
            case UnitOfWork.Skipped<Void, Void> skipped -> throw new IllegalStateException("A standalone unit of work "
                    + "admits no instance: " + skipped.reason());
        }
    }

    @Override
    public Void perform(WorkContext context) {
        context.recording(handlerCall);
        return null;
    }
}
