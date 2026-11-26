package io.github.gkosharovdev.sagaforge.spring.runtime;

import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.spring.transport.TransportLifecycle;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the {@link SagaRuntime} after the context refreshed, one phase before the transport starts
 * delivering ({@code specs/008-spring-boot-starter} FR-001, FR-005). Stopping the transport, which
 * happens first, stops all delivery into it; {@link #stop()} then stops the runtime's recovery
 * sweeps ({@link SagaRuntime#stop()}, specs/015-saga-operations). A later {@link #start()}, as in
 * {@code context.stop(); context.start()}, starts the runtime again.
 */
public final class SagaRuntimeLifecycle implements SmartLifecycle {

  /** One phase before {@link TransportLifecycle#PHASE}: starts first, stops last. */
  public static final int PHASE = TransportLifecycle.PHASE - 1;

  private final SagaRuntime runtime;
  private volatile boolean running;

  public SagaRuntimeLifecycle(SagaRuntime runtime) {
    this.runtime = runtime;
  }

  @Override
  public void start() {
    if (!runtime.isStarted()) {
      runtime.start();
    }
    running = true;
  }

  @Override
  public void stop() {
    runtime.stop();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }
}
