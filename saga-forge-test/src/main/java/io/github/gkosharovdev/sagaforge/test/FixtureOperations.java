package io.github.gkosharovdev.sagaforge.test;

import io.github.gkosharovdev.sagaforge.core.operations.InstanceCounts;
import io.github.gkosharovdev.sagaforge.core.operations.OperatorRequest;
import io.github.gkosharovdev.sagaforge.core.operations.RecoveryReport;
import io.github.gkosharovdev.sagaforge.core.operations.RepairResult;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstancePage;
import io.github.gkosharovdev.sagaforge.core.operations.SagaInstanceQuery;
import io.github.gkosharovdev.sagaforge.core.operations.SagaOperations;
import io.github.gkosharovdev.sagaforge.core.spi.SagaInstance;
import io.github.gkosharovdev.sagaforge.core.spi.SagaLifecycleEntry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The fixture's {@link SagaOperations}: the runtime's, delegated to, with the loop-back transport
 * drained after each repair and sweep, so the commands and events they caused are delivered as
 * after a published event ({@code specs/015-saga-operations}). Queries only delegate.
 */
final class FixtureOperations implements SagaOperations {

  private final Supplier<SagaOperations> delegate;
  private final Runnable drain;

  FixtureOperations(Supplier<SagaOperations> delegate, Runnable drain) {
    this.delegate = delegate;
    this.drain = drain;
  }

  @Override
  public Optional<SagaInstance> find(String sagaId) {
    return delegate.get().find(sagaId);
  }

  @Override
  public List<SagaInstance> findByAssociation(String property, String value) {
    return delegate.get().findByAssociation(property, value);
  }

  @Override
  public SagaInstancePage list(SagaInstanceQuery query) {
    return delegate.get().list(query);
  }

  @Override
  public InstanceCounts count(Set<String> sagaTypes) {
    return delegate.get().count(sagaTypes);
  }

  @Override
  public List<SagaLifecycleEntry> history(String sagaId) {
    return delegate.get().history(sagaId);
  }

  @Override
  public Map<String, Object> context(String sagaId) {
    return delegate.get().context(sagaId);
  }

  @Override
  public RepairResult resumeCompensation(String sagaId, OperatorRequest request) {
    return drained(delegate.get().resumeCompensation(sagaId, request));
  }

  @Override
  public RepairResult compensate(String sagaId, OperatorRequest request) {
    return drained(delegate.get().compensate(sagaId, request));
  }

  @Override
  public RepairResult markResolved(String sagaId, OperatorRequest request) {
    return drained(delegate.get().markResolved(sagaId, request));
  }

  @Override
  public RecoveryReport recover() {
    return drained(delegate.get().recover());
  }

  private <T> T drained(T result) {
    drain.run();
    return result;
  }
}
