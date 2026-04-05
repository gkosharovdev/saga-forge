package io.github.gkosharovdev.sagaforge.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a compensation handler. The {@code forStep} attribute references the
 * method name of the {@code @SagaStep} to compensate. The scanner validates
 * that the referenced method exists and is annotated with {@code @SagaStep}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SagaCompensation {

    /**
     * The method name of the {@code @SagaStep} that this compensation
     * handler reverses.
     */
    String forStep();
}
