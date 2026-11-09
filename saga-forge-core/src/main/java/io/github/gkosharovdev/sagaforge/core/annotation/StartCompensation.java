package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the step that handles a business-failure event, such as a rejected delivery or a stock shortage
 * ({@code specs/006-saga-compensation} FR-001). Must co-occur with {@code @SagaStep} and never with
 * {@code @StartSaga} or {@code @EndSaga} (rule R20).
 *
 * <p>The step runs like any other; when its unit of work commits, the saga instance enters
 * {@code COMPENSATING} and the engine runs the {@code @SagaCompensation} of every other completed step, most
 * recently completed first. The method's name is the failure outcome recorded in the instance's
 * {@code CompensationTrigger}, the way an {@code @EndSaga} step's name is the success outcome. Data the
 * compensations need from the failure event is written to {@code SagaContext} by this step: the event itself is not
 * passed to them.</p>
 *
 * <pre>{@code
 * @StartCompensation
 * @SagaStep(associationProperty = "orderId")
 * public void rejectDelivery(DeliveryRejectedEvent event) {
 *     context.putState("rejectionReason", event.reason());
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StartCompensation {
}
