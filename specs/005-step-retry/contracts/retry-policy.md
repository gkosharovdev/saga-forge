# Contract: Retry Policy and Failure Classification

**Feature**: `005-step-retry` | Spec FR-008 to FR-014b | Research §5 to §7

## Declaring a policy

```java
@SagaStep(associationProperty = "orderId",
          retry = @RetryPolicy(maxAttempts = 3,
                               initialBackoffMillis = 200,
                               multiplier = 2.0,
                               maxBackoffMillis = 1_000,
                               retryOn = PaymentGatewayTimeoutException.class))
public void processPayment(PaymentProcessedEvent event) { ... }
```

| Attribute | Default | Valid values (Property 29) |
|---|---|---|
| `maxAttempts` | `1` | `>= 1` |
| `initialBackoffMillis` | `0` | `>= 0` |
| `multiplier` | `1.0` | `>= 1.0`, finite |
| `maxBackoffMillis` | `0` (= initial) | `0` or `>= initialBackoffMillis` |
| `retryOn` | `{}` | no `java.lang.Error` subtypes |

With `maxAttempts == 1`, every other attribute must keep its default. Violations are scanner errors in
`validate`/`validateAll`, worded like the existing ones:
`"@SagaStep method {methodName} in {className} has an invalid retry policy: {attribute} {reason}"` (rule R19 in
`.kiro/specs/saga-forge-framework/design.md`, which lists the exact `{reason}` texts).

## Wait before attempt k (k >= 2)

`wait(k) = min(initialBackoffMillis * multiplier^(k-2), cap)` where `cap = maxBackoffMillis == 0 ?
initialBackoffMillis : maxBackoffMillis`. Total = `sum wait(k)` for `k = 2..maxAttempts`. Engine startup rejects a
step whose total exceeds `maxTotalRetryBackoff` (default 30 s): `StartupReport` error
`"@SagaStep {saga}.{step}: total retry backoff {total} ms exceeds the engine maximum {max} ms"`.

## Classification of the failure that rolled back attempt k

Input: the failure 004 already selects (first failure reported by the command gateway, else the thrown one) and the
policy of the step that was running (`F`).

1. If the failure is a `java.lang.Error` → `UNCLASSIFIED`.
2. Walk `failure, failure.getCause(), …` (stop on a repeat). First element that implements `FinalFailure` →
   `FINAL`; first that implements `TransientFailure` → `TRANSIENT` (an element implementing both counts as `FINAL`).
3. No marker: any element `isInstance` of a type in `F.retry.retryOn` → `TRANSIENT`.
4. Otherwise → `UNCLASSIFIED`.

| Classification | k < maxAttempts(F) | k = maxAttempts(F) |
|---|---|---|
| `TRANSIENT` | attempt write, pause, attempt k+1 | `FAILED`, recorded `EXHAUSTED`, attempts = k |
| `FINAL` | `FAILED`, `FINAL`, attempts = k | same |
| `UNCLASSIFIED` | `FAILED`, `UNCLASSIFIED`, attempts = k | same |

Not classified, not retried, unchanged from 004: write-time `SagaConcurrencyConflictException` (`NOT_HANDLED`),
failure of the locked read or of the `FAILED` write (propagates).

## Marker types (core, `core.failure`)

```java
public interface TransientFailure {}   // retry may help
public interface FinalFailure {}       // retrying cannot help
public enum FailureClassification { TRANSIENT, FINAL, EXHAUSTED, UNCLASSIFIED }
```

`006-saga-compensation` reads `SagaFailure.classification()`: it compensates on `FINAL` and `EXHAUSTED` only.
