package io.github.gkosharovdev.sagaforge.engine.fixtures.idempotency;

import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import java.util.ArrayList;
import java.util.List;

/** Service handler passing the command identity to the provider as its idempotency key. */
public final class ChargeService {

    private final FakePaymentProvider provider;
    private final Faults faults;
    private final List<CommandId> seen = new ArrayList<>();

    public ChargeService(FakePaymentProvider provider, Faults faults) {
        this.provider = provider;
        this.faults = faults;
    }

    @CommandHandler
    public void handle(Charge command, CommandId commandId) {
        seen.add(commandId);
        provider.charge(commandId.value(), command.amount());
        faults.check(Faults.IN_CHARGE_HANDLER);
    }

    /** Every identity this handler received, including repeats. */
    public List<CommandId> seen() {
        return List.copyOf(seen);
    }
}
