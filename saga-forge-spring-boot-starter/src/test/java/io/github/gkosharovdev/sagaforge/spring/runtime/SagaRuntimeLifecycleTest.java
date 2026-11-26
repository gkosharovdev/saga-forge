package io.github.gkosharovdev.sagaforge.spring.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.runtime.SagaRuntime;
import io.github.gkosharovdev.sagaforge.runtime.fixtures.compensation.UnwindFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code stop()} stops the runtime ({@code specs/015-saga-operations} tasks.md T053). */
class SagaRuntimeLifecycleTest {

  @Test
  @DisplayName("stop() stops the started runtime: operations are refused afterwards")
  void stopStopsTheRuntime() {
    SagaRuntime runtime = UnwindFixture.withEveryCompensation().engine();
    SagaRuntimeLifecycle lifecycle = new SagaRuntimeLifecycle(runtime);
    lifecycle.start();
    assertThat(lifecycle.isRunning()).isTrue();

    lifecycle.stop();

    assertThat(lifecycle.isRunning()).isFalse();
    assertThat(runtime.isStarted()).isFalse();
    assertThatThrownBy(runtime::operations).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("start() after stop() starts the runtime again")
  void startAfterStop() {
    SagaRuntime runtime = UnwindFixture.withEveryCompensation().engine();
    SagaRuntimeLifecycle lifecycle = new SagaRuntimeLifecycle(runtime);
    lifecycle.start();
    lifecycle.stop();

    lifecycle.start();

    assertThat(lifecycle.isRunning()).isTrue();
    assertThat(runtime.isStarted()).isTrue();
    assertThat(runtime.operations()).isNotNull();
  }
}
