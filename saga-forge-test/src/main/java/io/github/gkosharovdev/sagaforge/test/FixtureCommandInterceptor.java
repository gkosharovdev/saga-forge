package io.github.gkosharovdev.sagaforge.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.gkosharovdev.sagaforge.core.spi.CommandInterceptor;
import io.github.gkosharovdev.sagaforge.core.spi.CommandInvocation;

/**
 * Sees every command handling of the fixture's runtime (research §2): applies the failure rules, records the command,
 * and runs its handler only if the test registered it (FR-011, FR-012). A record joins the handling transaction:
 * it is kept among the committed commands only if that transaction commits (research §3).
 */
final class FixtureCommandInterceptor implements CommandInterceptor {

    private final FixtureTransactions transactions;
    private final List<CommandFailureRule> rules;
    private final Set<Class<?>> handledTypes;
    private final List<RecordedCommand> attempts = new ArrayList<>();
    private final List<RecordedCommand> committed = new ArrayList<>();
    private boolean deliveringOutbox;

    FixtureCommandInterceptor(FixtureTransactions transactions, List<CommandFailureRule> rules,
            Set<Class<?>> handledTypes) {
        this.transactions = transactions;
        this.rules = List.copyOf(rules);
        this.handledTypes = Set.copyOf(handledTypes);
    }

    @Override
    public void intercept(CommandInvocation invocation) {
        Object command = invocation.command();
        RecordedCommand.Delivery delivery = deliveringOutbox
                ? RecordedCommand.Delivery.OUTBOX : RecordedCommand.Delivery.IN_UNIT_OF_WORK;
        int attempt = 1 + (int) attemptsSnapshot().stream()
                .filter(earlier -> earlier.commandId().equals(invocation.commandId())).count();
        for (CommandFailureRule rule : rules) {
            if (rule.matches(command)) {
                record(new RecordedCommand(command, invocation.commandId(), delivery, attempt, false));
                throw rule.fail();
            }
        }
        boolean handle = invocation.hasHandler() && handledTypes.contains(command.getClass());
        record(new RecordedCommand(command, invocation.commandId(), delivery, attempt, handle));
        if (handle) {
            invocation.proceed();
        }
    }

    /** Marks the handlings that follow as deliveries of outbox commands. */
    synchronized void deliveringOutbox(boolean delivering) {
        this.deliveringOutbox = delivering;
    }

    synchronized List<RecordedCommand> attemptsSnapshot() {
        return List.copyOf(attempts);
    }

    synchronized List<RecordedCommand> committedSnapshot() {
        return List.copyOf(committed);
    }

    private synchronized void record(RecordedCommand command) {
        attempts.add(command);
        transactions.active().ifPresentOrElse(transaction -> transaction.onCommit(() -> commit(command)),
                () -> commit(command));
    }

    private synchronized void commit(RecordedCommand command) {
        committed.add(command);
    }
}
