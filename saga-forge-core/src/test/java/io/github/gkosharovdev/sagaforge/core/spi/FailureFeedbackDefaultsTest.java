package io.github.gkosharovdev.sagaforge.core.spi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.failure.FailureClassification;
import io.github.gkosharovdev.sagaforge.core.spi.store.DeliveryFailure;
import io.github.gkosharovdev.sagaforge.core.spi.store.MessageStore;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The default methods {@code specs/012-outbox-command-failure-feedback} adds to released SPIs:
 * implementations written before them still compile, and calling the new method fails loudly.
 */
class FailureFeedbackDefaultsTest {

  @Test
  @DisplayName("a receiver that predates receiveFailure rejects a report, naming its class")
  void receiverDefault() {
    CommandReceiver legacy = delivery -> {};
    CommandFailure report =
        new CommandFailure(
            CommandId.of("saga-1", "pay", "com.example.Pay", 1),
            FailureClassification.FINAL,
            "com.example.Declined",
            1);

    assertThatThrownBy(() -> legacy.receiveFailure(report, tx -> {}))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining(legacy.getClass().getName())
        .hasMessageContaining("does not receive command failures");
  }

  @Test
  @DisplayName("a message store that predates the transactional setAside rejects it")
  void messageStoreDefault() {
    InvocationHandler onlyDefaults =
        (proxy, method, args) -> {
          if (!method.isDefault()) {
            throw new AssertionError("unexpected call to " + method.getName());
          }
          return InvocationHandler.invokeDefault(proxy, method, args);
        };
    MessageStore legacy =
        (MessageStore)
            Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {MessageStore.class}, onlyDefaults);

    assertThatThrownBy(
            () ->
                legacy.setAside(
                    null,
                    "relay",
                    "m-1",
                    new DeliveryFailure("com.example.Declined", null, Instant.EPOCH),
                    "FINAL_FAILURE"))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("cannot set a message aside in a transaction");
  }
}
