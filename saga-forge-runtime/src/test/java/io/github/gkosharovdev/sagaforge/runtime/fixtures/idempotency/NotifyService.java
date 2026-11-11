package io.github.gkosharovdev.sagaforge.runtime.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import java.util.ArrayList;
import java.util.List;

/** Service handler that records the identities it receives. */
public final class NotifyService {

    private final List<CommandId> seen = new ArrayList<>();

    @CommandHandler
    public void handle(Notify command, CommandId commandId) {
        seen.add(commandId);
    }

    public List<CommandId> seen() {
        return List.copyOf(seen);
    }
}
