package io.github.gkosharovdev.sagaforge.core.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gkosharovdev.sagaforge.core.annotation.Command;
import io.github.gkosharovdev.sagaforge.core.annotation.CommandHandler;
import io.github.gkosharovdev.sagaforge.core.annotation.EndSaga;
import io.github.gkosharovdev.sagaforge.core.annotation.Saga;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaId;
import io.github.gkosharovdev.sagaforge.core.annotation.SagaStep;
import io.github.gkosharovdev.sagaforge.core.annotation.StartSaga;
import io.github.gkosharovdev.sagaforge.core.command.CommandId;
import io.github.gkosharovdev.sagaforge.core.metadata.SagaMetadata;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent;
import io.github.gkosharovdev.sagaforge.core.scanner.fixtures.TestEvent2;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rules R32 and R33 and Property 50 (a) ({@code specs/019-process-metadata}
 * contracts/saga-code.md): at most one {@code SagaMetadata} field on a saga, at most one {@code
 * SagaMetadata} parameter on a command handler.
 */
class SagaMetadataValidationTest {

  @Saga
  static class OneMetadataFieldSaga {
    @SagaId private String sagaId;
    private SagaMetadata metadata;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleStart(TestEvent event) {}

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void handleEnd(TestEvent2 event) {}
  }

  @Saga
  static class TwoMetadataFieldsSaga {
    @SagaId private String sagaId;
    private SagaMetadata metadata;
    private SagaMetadata moreMetadata;

    @StartSaga
    @SagaStep(associationProperty = "orderId")
    public void handleStart(TestEvent event) {}

    @EndSaga
    @SagaStep(associationProperty = "orderId")
    public void handleEnd(TestEvent2 event) {}
  }

  @Command
  record Charge(String id) {}

  @Command
  record Refund(String id) {}

  static final class PaymentService {
    @CommandHandler
    public void charge(Charge command, CommandId commandId, SagaMetadata metadata) {}

    @CommandHandler
    public void refund(Refund command, SagaMetadata metadata, SagaMetadata again) {}
  }

  private final AnnotationScanner scanner = new ReflectiveAnnotationScanner();

  @Test
  @DisplayName("R32: one SagaMetadata field is valid")
  void oneField() {
    assertThat(scanner.validate(OneMetadataFieldSaga.class).errors()).isEmpty();
  }

  @Test
  @DisplayName("R32: two SagaMetadata fields are an error naming the saga and the count")
  void twoFields() {
    assertThat(scanner.validate(TwoMetadataFieldsSaga.class).errors())
        .containsExactly(
            "@Saga class TwoMetadataFieldsSaga must declare at most one SagaMetadata field, found 2");
    assertThat(scanner.validateAll(List.of(TwoMetadataFieldsSaga.class)).allErrors())
        .contains(
            "@Saga class TwoMetadataFieldsSaga must declare at most one SagaMetadata field, found 2");
  }

  @Test
  @DisplayName("R33: one SagaMetadata handler parameter is valid, two are an error")
  void handlerParameters() {
    List<String> errors =
        scanner.validateAll(List.of(Charge.class, Refund.class, PaymentService.class)).allErrors();

    assertThat(errors)
        .containsExactly(
            "@CommandHandler method refund in PaymentService must declare at most one"
                + " SagaMetadata parameter, found 2");
  }
}
