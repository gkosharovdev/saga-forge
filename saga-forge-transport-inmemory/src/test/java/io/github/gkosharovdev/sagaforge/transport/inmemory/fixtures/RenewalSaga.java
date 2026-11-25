package io.github.gkosharovdev.sagaforge.transport.inmemory.fixtures;

import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandGateway;
import io.github.gkosharovdev.sagaforge.core.command.ScheduledCommandId;
import io.github.gkosharovdev.sagaforge.core.lifecycle.SagaContext;
import java.time.Duration;
import java.time.Instant;

/**
 * Schedules a command ({@code specs/017-saga-deadlines} US4): {@code @StartSaga onStarted}
 * schedules the renewal {@link ChargeCard}; {@code @EndSaga onCancelled} cancels it. A subscription
 * id starting with {@link #BROKEN} makes the start step fail after scheduling. All on {@code
 * associationProperty = "subscriptionId"}.
 */
@Saga
public final class RenewalSaga {

  public static final String BROKEN = "broken-";

  @SagaId private String sagaId;

  private SagaContext context;

  private CommandGateway commandGateway;

  @StartSaga
  @SagaStep(associationProperty = "subscriptionId")
  public void onStarted(SubscriptionStarted event) {
    ScheduledCommandId renewal =
        commandGateway.schedule(
            new ChargeCard(event.subscriptionId()),
            Instant.now().plus(Duration.parse(event.chargeIn())));
    context.putState("renewal", renewal.value());
    if (event.subscriptionId().startsWith(BROKEN)) {
      throw new IllegalStateException("subscription store unavailable");
    }
  }

  @EndSaga
  @SagaStep(associationProperty = "subscriptionId")
  public void onCancelled(SubscriptionCancelled event) {
    context.putState(
        "renewalCancelled",
        String.valueOf(
            commandGateway.cancel(
                new ScheduledCommandId(context.getState("renewal", String.class).orElseThrow()))));
  }
}
