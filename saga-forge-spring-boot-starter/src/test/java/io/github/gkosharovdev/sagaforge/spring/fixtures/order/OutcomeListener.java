package io.github.gkosharovdev.sagaforge.spring.fixtures.order;

import io.github.gkosharovdev.sagaforge.core.outcome.SagaFailed;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A transactional listener for failed sagas ({@code specs/011-saga-outcome-events} C4): runs after
 * the commit.
 */
public class OutcomeListener {

  private final List<SagaFailed> failed = new CopyOnWriteArrayList<>();

  @TransactionalEventListener
  public void on(SagaFailed event) {
    failed.add(event);
  }

  public List<SagaFailed> failed() {
    return List.copyOf(failed);
  }
}
