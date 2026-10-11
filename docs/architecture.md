# Architecture

This document describes the target design of Saga Forge: what is built today, what is planned, and the
reasoning behind the boundaries between modules. See the root [README.md](../README.md) for the pitch and
[use-cases.md](use-cases.md) for worked examples.

## Status at a glance

| Layer | State |
|---|---|
| Annotation model (`@Saga`, `@SagaId`, `@SagaStep`, `@StartSaga`, `@EndSaga`, `@SagaCompensation`, `@DomainEvent`, `@Command`, `@ReadModel`) | Implemented, tested |
| `AnnotationScanner` / `ReflectiveAnnotationScanner` / `SagaValidationResult` | Implemented, tested |
| `SagaContext` / `SagaLifecycle` | Implemented, tested |
| SPI (`StorageProvider`, `EventDispatcher`) | Defined, no implementations |
| Saga engine runtime | Not implemented |
| Storage providers (Postgres, MySQL, MongoDB) | Not implemented |
| Transports (in-memory, Kafka) | Not implemented |
| Spring Boot autoconfiguration | Not implemented |

## Module responsibilities

- **`saga-forge-core`** — owns the annotation model, the scanner that validates and reflects over `@Saga`
  classes, `SagaContext`/`SagaLifecycle`, and the SPI contracts. Zero runtime dependencies, including no
  Spring dependency. This is deliberate: the annotation model and validation rules must be usable from a
  future non-Spring runtime without forcing a Spring dependency on every consumer.
- **`saga-forge-spring-boot-starter`** — the only module users explicitly add for wiring. Detects, via
  `@ConditionalOnClass`, which storage and transport dependencies are on the classpath, and assembles the
  engine accordingly. There is no `SagaEngineConfig` for a user to hand-build; if autoconfiguration can't
  resolve an unambiguous choice (e.g. two storage providers on the classpath), it should fail fast with a
  clear error rather than guess.
- **`saga-forge-storage-{postgres,mysql,mongodb}`** — each implements `StorageProvider` for one backend.
  Exactly one should be on the classpath per application. These are the only three storage backends in
  scope; adding a fourth is a scope decision, not an implementation detail.
- **`saga-forge-transport-{inmemory,kafka}`** — each implements `EventDispatcher`. In-memory wraps Spring's
  `ApplicationEventPublisher`; Kafka wraps a `KafkaTemplate`/consumer pair. These are the only two
  transports in scope. If neither is on the classpath, the starter falls back to in-memory as the default
  rather than failing, since in-memory requires no external infrastructure.
- **`saga-forge-example`** — a runnable saga (`OrderProcessingSaga`) used as a living spec. It must always
  compile against whatever the annotation model currently supports; if an annotation's contract changes, the
  example changes with it in the same change.

## Autoconfiguration detection matrix (target)

| Classpath signal | Storage provider selected |
|---|---|
| `org.postgresql.Driver` + Postgres JDBC/R2DBC starter | `saga-forge-storage-postgres` |
| MySQL driver | `saga-forge-storage-mysql` |
| MongoDB driver | `saga-forge-storage-mongodb` |
| None of the above | Autoconfiguration fails fast — a saga needs durable storage, so there is no silent fallback |

| Classpath signal | Transport selected |
|---|---|
| `org.apache.kafka.clients.producer.KafkaProducer` | `saga-forge-transport-kafka` |
| Neither Kafka nor any other broker client present | `saga-forge-transport-inmemory` (default) |

Exactly one storage provider and one transport are expected to resolve. Autoconfiguration does not attempt to
support running two storage providers or two transports simultaneously.

## Why orchestration on an event-driven substrate

Steps are triggered by domain events — delivered either as Spring application events or Kafka records. At the
transport level this is choreography: each handler reacts to an event without a central dispatcher deciding
what happens next. But every `@Saga` instance keeps durable, single-process state (via `SagaContext` and the
`StorageProvider`), so at any point you can ask "where is this saga, and what has it already done" without
replaying every event it ever reacted to. That queryable, durable process state is what makes this
orchestration rather than pure choreography — it is layered on top of an event-driven delivery mechanism, not
a replacement for one.

This process state is also the intended foundation for **downstream policies**: a policy that needs to know
"has the payment step for this booking already emitted a command" should be able to answer that by reading
saga process state (through a `@ReadModel`-backed repository) rather than re-deriving it from raw event
history.

## Truthful state and transaction boundaries

A step's completion is only ever recorded if the step's own business effect (the command handler it
triggers, or the local side effect it performs) committed. Saga state persistence must happen in the *same*
transaction as that side effect — never as an independent "fire and forget" write afterward. This is inherited
directly from production experience in `docs/use-cases.md`: a step recorded as completed despite its business
effect being rolled back is a worse failure mode than the step simply failing, because it silently breaks the
saga's ability to resume or retry correctly.

## Feature gaps

The following are requirements a production-grade saga runtime must satisfy, derived from real multi-step,
multi-aggregate orchestration use cases (see [use-cases.md](use-cases.md)). None of them are implemented in
`saga-forge-core` today. They are listed here as the backlog for the engine/starter work, each phrased as a
capability the framework must provide — not as an implementation prescription.

### Queryable process-level state (highest priority)

Today a step can only see the fields carried on the single event instance it receives. There is no way for a
step to read a value that an *earlier* step in the same saga instance computed or loaded, unless that value
was manually threaded forward through a chain of growing event types — which does not scale past a handful of
steps and forces every event type to carry the full upstream state it never otherwise needs.

The framework must provide a **general, queryable process-state store per saga instance**, distinct from
`SagaContext`'s existing history records (`HandledEventRecord`/`EmittedCommandRecord`/`CompletedStepRecord`,
which record *what happened*, not arbitrary business values). Concretely:

- A step must be able to associate a named value with the current saga instance (e.g.
  `SagaLifecycle.putAssociatedState("apartment", apartment)`), and a *later* step — reflectively invoked in a
  separate dispatch, possibly on a different thread or a different instance entirely if the transport is
  Kafka — must be able to read it back by name and type (`SagaLifecycle.getAssociatedState("apartment",
  Apartment.class)`).
- Because steps in different dispatches cannot share Java object identity or a live `ThreadLocal`, associated
  state must round-trip through the `StorageProvider`, not just survive within one invocation. This means
  `StorageProvider` needs a way to persist arbitrary business objects per saga instance, not only the
  annotation-scanner-known record types — most likely a serialized blob column per storage backend, with a
  decision needed on which serialization format `saga-forge-core` can depend on (or stay serialization-agnostic
  and delegate the choice to each `StorageProvider` implementation).
- This is also the mechanism that should back the "process state available to downstream policies" principle
  already stated in the "Why orchestration on an event-driven substrate" section above — a `@ReadModel`-backed
  policy reading saga state should be reading through this same associated-state store, not a parallel
  mechanism.

### Idempotent step execution

A step handling the same event twice (at-least-once delivery, consumer rebalance, manual retry) must not
re-emit its command or re-run its side effect. This requires a uniqueness check — most naturally a unique
constraint on `(saga_id, step_name)` at the storage layer — consulted before a step's handler runs, so a
duplicate dispatch is recognized and skipped rather than re-executed.

A second, coarser-grained idempotency concern also needs coverage: deduplicating the *triggering command*
itself (e.g. two requests to accept the same booking arriving concurrently) before a saga instance is even
created or resumed, not just deduplicating individual step execution within an existing instance.

### Per-step retry policy with failure classification

Each step must be able to declare its own retry policy (max attempts, backoff strategy) because different
steps call different downstream systems with different failure characteristics. Retries must apply only to
transient infrastructure failures (timeouts, connection errors) — never to domain rule violations, which are
terminal and resolve via compensation or intervention (see below). This means the framework needs its own
failure-classification contract (e.g. a marker interface or exception hierarchy a step can throw to signal
"transient" vs. "terminal") that doesn't depend on any particular retry library's exception taxonomy, since
`saga-forge-core` has zero runtime dependencies and the eventual Spring starter should not require step
authors to catch or throw a Spring-specific exception type just to get correct retry behavior.

Retry attributes do not belong on `@SagaStep` as an alias into a third-party retry annotation — they must be
framework-owned primitive data (attempt count, backoff parameters) that the engine itself reads and executes,
so the same `@SagaStep` contract can eventually be honored by a non-Spring runtime. Any addition of retry
attributes to `@SagaStep` must be proposed as a change to `.kiro/specs/saga-forge-framework/design.md` first,
since it changes the annotation's formal contract and validation rules.

### Transaction boundary relative to the triggering dispatch

The "truthful state" principle already requires that a step's completion is recorded in the same transaction
as the step's own business effect. What's still undecided is the transaction boundary *relative to the event
dispatch that triggered the step* — whether each step attempt commits independently of (and is isolated from)
whatever transaction the triggering dispatch arrived in. This affects whether a later step, failing after an
earlier step, can still see that earlier step's committed effect, and needs to be settled as an explicit
engine-level decision rather than left to each storage/transport implementation to decide inconsistently.

### Failure categorization and automatic compensation dispatch

A step's failure must resolve to exactly one of two outcomes:

- **Compensatable** — a `@SagaCompensation(forStep = "...")` method exists for the failed step (or for a
  prior completed step whose effect must now be undone), and running it returns the aggregate(s) involved to
  a consistent state.
- **Requires intervention** — no compensation applies, or compensation itself cannot proceed safely. The saga
  must be left in a terminal, inspectable failed state (not silently retried forever) so a human can decide
  the next action.

`saga-forge-core` does not yet model "requires intervention" as a first-class status — today a failed saga is
just a saga with no further steps able to progress, which is indistinguishable from a saga that simply hasn't
run yet. Beyond adding that status, the engine must decide **when compensation is invoked automatically**
(which failure categories, from which step) versus requiring the step author to invoke it explicitly — today
nothing in `saga-forge-core` specifies this, and declaring `@SagaCompensation(forStep = "...")` only makes the
compensation *discoverable*, not *automatically wired* to any particular failure.

## jMolecules consideration

[jMolecules](https://github.com/xmolecules/jmolecules) provides DDD/architecture marker annotations
(`@AggregateRoot`, `@Entity`, `@Service`, etc.) and structural tests that verify a codebase actually respects
them. Saga Forge's own markers (`@DomainEvent`, `@Command`, `@ReadModel`) already play a similar role, but
narrower — they mark types for the scanner, not for architectural verification.

The intended integration, not yet decided or built, is **optional interop, not a hard dependency**:

- `saga-forge-core` should keep its own `@DomainEvent`/`@Command`/`@ReadModel` annotations as the primary
  contract the scanner validates against, since the framework cannot require every consumer to adopt
  jMolecules.
- A thin, separately-versioned adapter could recognize jMolecules' `@Event`, `@Service`-style annotations as
  equivalent, so teams already using jMolecules don't have to double-annotate their types. This is additive
  and should not change `saga-forge-core`'s zero-dependency posture.
- Until this is designed in detail, treat jMolecules support as unimplemented and don't assume any
  recognition of jMolecules annotations exists.

## Vocabulary

Saga Forge borrows its vocabulary from DDD / EventStorming rather than inventing new terms.

- **Command** — an instruction aimed at a single aggregate, expressing intent to change state. Saga steps
  return commands; they never mutate another aggregate's state directly. Marked with `@Command`.
- **Event** (Domain Event) — a fact that already happened, published by an aggregate after a state change
  committed. Saga steps react to events; they never react to commands. Marked with `@DomainEvent`.
- **Aggregate** — the consistency boundary that owns the state a command changes and the events that record
  that change. Saga Forge never reaches inside an aggregate; it only sends commands to one and listens for its
  events.
- **Policy** — the cross-aggregate rule a saga encodes: "when X happens, Y should happen." A `@Saga` class is
  a declarative expression of one or more related policies, scoped by a shared association property.
- **Projection** (Read Model) — a denormalized view built from events, used to enrich a step's decision
  without reaching into another aggregate's internals. Accessed via a repository injected into the saga;
  types exposed this way are marked `@ReadModel`.
- **Saga** — the durable, single-process orchestrator for one instance of a policy, identified by `@SagaId`
  and correlated to events via `associationProperty`. Not to be confused with the Saga pattern in the generic
  distributed-transaction sense — here it specifically means an annotated class matching this framework's
  model.
- **Step** — one unit of saga behavior: react to one event type, optionally emit one command, optionally
  start (`@StartSaga`) or end (`@EndSaga`) the saga's lifecycle. Declared with `@SagaStep`.
- **Compensation** — the declared undo for a step whose business effect must be reversed after a later step
  fails. Declared with `@SagaCompensation(forStep = "...")`, scoped to exactly one step by name.
