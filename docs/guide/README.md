# Saga Forge user guide

This guide is for applications that **use** Saga Forge: you add the published artifacts to your build and write sagas,
command handlers and aggregates. It does not cover how Saga Forge itself is built (specs, gap register, internal
design); that material lives in the repository's `AGENTS.md`, `docs/architecture.md` and `specs/`.

Every code snippet in this guide is copied from compiling code in this repository; the source file is named under each
snippet, so you can open it for the imports and the surrounding class.

| Page | Read it when you want to |
|---|---|
| [Getting started](getting-started.md) | add Saga Forge 0.1.0 to a Spring Boot 4.1 / Java 21 application and create the schema |
| [Model reference](model-reference.md) | know where each annotation goes, what it needs next to it, and its attributes |
| [Validation rules](validation-rules.md) | turn a startup error into a fix |
| [Recipes](recipes.md) | copy a working pattern: multi-aggregate unit of work, compensation, retry and idempotency, outbox commands, a modular monolith |
| [Testing](testing.md) | test a saga with `SagaFixture`, in memory |
| [Operations](operations.md) | configure properties, Actuator, OpenTelemetry, schema upgrades |
| [Limitations](limitations.md) | know what to design around in 0.1.0 |

API reference: the Javadoc of every published module is on
[javadoc.io](https://javadoc.io/doc/io.github.gkosharovdev/saga-forge-core) (`saga-forge-core`, `saga-forge-runtime`,
`saga-forge-test`, `saga-forge-spring-boot-starter`, `saga-forge-storage-postgres`, `saga-forge-transport-inmemory`).
The starter's own [README](../../saga-forge-spring-boot-starter/README.md) lists everything it assembles and how to
replace each piece.

## Vocabulary

Saga Forge uses DDD / EventStorming terms (full glossary in
[architecture.md § Vocabulary](../architecture.md#vocabulary)):

- **Command**: an instruction to change state, handled by exactly one `@CommandHandler`. A saga step sends it through
  `CommandGateway`; a step never returns one.
- **Domain event**: a fact about one aggregate that already happened. Recorded by handlers with
  `DomainEvents.record(...)`, or published by application code with `EventPublisher`.
- **Aggregate**: the consistency boundary a command changes. Marked `@Aggregate`, identified by one
  `@AggregateIdentifier` field; its persistence is your code (a Spring Data repository or an
  `@AggregateLoader`/`@AggregateSaver`).
- **Policy**: the cross-aggregate rule "when X happens, Y should happen" that a saga encodes.
- **Saga**: the durable process for one instance of a policy, a `@Saga` class whose instances are correlated by an
  association property.
- **Step**: a `@SagaStep` method reacting to one domain event type.
- **Compensation**: a `@SagaCompensation` method that undoes a committed step when the saga fails after it.
- **Unit of work**: the transaction in which one or more steps, the commands they send and the handlers' changes commit
  together, or roll back together.
