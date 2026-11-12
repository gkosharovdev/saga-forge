# Contract: Command interceptor (core SPI + runtime builder)

Additive; JDK only (Constitution Principle I).

```java
package io.github.gkosharovdev.sagaforge.core.spi;

/** Sees every command the runtime is about to handle; may fail it, complete it, or let it proceed. */
@FunctionalInterface
public interface CommandInterceptor {
    void intercept(CommandInvocation invocation);

    /** The default: proceed. */
    static CommandInterceptor proceeding() { return CommandInvocation::proceed; }
}

public interface CommandInvocation {
    Object command();
    CommandId commandId();       // as assigned by the runtime (005); unscoped outside a saga unit of work
    boolean hasHandler();        // a @CommandHandler route exists for command().getClass()
    void proceed();              // runs the route; NoCommandHandlerException if hasHandler() is false
}
```

`SagaRuntime.Builder.commandInterceptor(CommandInterceptor)`: at most one; default `proceeding()`.

## Obligations of the runtime

1. The interceptor is called once per handling of a command: a command sent from a step or compensation in a saga unit
   of work, a command sent outside a unit of work (non-saga unit of work), and an outbox command delivered through
   `CommandReceiver`. A command whose identity already committed for the instance (005 FR-004, skipped) is not handed
   to the interceptor.
2. It runs on the handling thread, inside the unit of work's transaction, with the command's identity bound (so a
   `CommandId` handler parameter sees it).
3. An exception thrown by `intercept` is handled exactly as an exception of the handler: rollback-only, classification,
   retry, compensation.
4. Returning without `proceed()` completes the command as handled; its identity is recorded with the step as for a
   handled command.
5. `proceed()` at most once; a second call throws `IllegalStateException`.
6. With the default interceptor, behaviour is unchanged (all existing runtime tests pass unmodified).

## Formal spec

`.kiro/specs/saga-forge-framework/design.md` gains Property 36 (command interception): for any command handled by the
runtime, the interceptor is invoked exactly once per handling, inside the unit of work, and an interceptor that only
proceeds is indistinguishable from no interceptor.
