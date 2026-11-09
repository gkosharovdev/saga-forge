package io.github.gkosharovdev.sagaforge.engine.fixtures.compensation;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;

/** Makes {@link ChainService} record {@link BDone} then {@link CDone}, so B and C run in A's unit of work. */
@Command
public record Chain(String orderId) {
}
