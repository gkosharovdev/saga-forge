# Contract: Command Identity and Idempotent Handling

**Feature**: `005-step-retry` | Spec FR-001 to FR-007 | Research §1 to §4

## Assigning identities

Inside a saga unit of work, each `CommandGateway.send(command)` from step `S` of instance `I` gets
`CommandId.of(I, S, command.getClass().getName(), n)`, where `n` counts commands of that type sent by this run of
`S`, starting at 1. A handler sending a further command from within its own call assigns it under the same step `S`.
Outside a saga unit of work, `CommandId.unscoped(type)` (random `value`, `sagaScoped == false`).

Guarantee: for the same instance, step, type and position, `value` is identical in every attempt, every redelivery
and every process (pure function of the four components, research §1).

## Receiving the identity in a handler

```java
public class PaymentService {
    @CommandHandler
    public void handle(ProcessPaymentCommand command, CommandId commandId) {
        provider.charge(command.orderId(), command.amount(), commandId.value()); // idempotency key
    }
}
```

Any `@CommandHandler` form (service method, aggregate instance method, aggregate constructor or static factory) may
declare one `CommandId` parameter. The engine's built-in resolver supplies it and takes precedence over host
`HandlerParameterResolver`s. Startup reports an error if a handler declares more than one.

## Already-handled commands

Before routing, the gateway checks the identity against `handledCommands` of the instance's committed
`StepRecord`s (loaded under the unit of work's lock):

| Found | Behaviour |
|---|---|
| no | route the command; on success add the identity to the running step's `handledCommands` |
| yes | do not invoke the handler; `send` returns normally; log `saga.command.skipped` with the identity |

A handler failure leaves the identity unrecorded (the unit of work rolls back).

## Duplicate events

| Situation | Outcome |
|---|---|
| Triggering step `COMPLETED` on the instance (any status, including terminal) | `HandlingOutcome.DUPLICATE`, transaction rolled back, nothing written, log `saga.event.duplicate` |
| Triggering step `NOT_RUN` | normal handling |
| Start event for an existing association | `DUPLICATE_START` (unchanged, 004) |
| In-process recorded event for a step already completed (committed or earlier in this run) | not queued; buffered and handed off like any other non-queued event |

`DUPLICATE` is checked after the locked read and before the terminal-instance check (`UNEXPECTED`).
