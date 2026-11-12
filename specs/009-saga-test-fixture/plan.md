# Implementation Plan: Saga Test Fixture

**Branch**: `009-saga-test-fixture` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/009-saga-test-fixture/spec.md`

## Summary

Add a published, framework-neutral test library, `saga-forge-test`, with a given/when/then `SagaFixture`. It builds
and starts the shipped `SagaRuntime` over in-memory plug-ins it owns:
- a transaction boundary with commit/rollback callbacks;
- storage, a context store with the durable-value check, and an aggregate store;
- a synchronous loop-back dispatcher and outbox;
- a controllable clock, a retry pause that advances it, and deterministic ids.

Given events and the event under test go through `SagaRuntime.receive`. Expectations read the stored instances, the
context records and the commands recorded by a new **command interceptor**: one additive core SPI
(`CommandInterceptor`/`CommandInvocation`) and runtime builder option. The interceptor is what makes record-and-answer
possible (a command with no handler is recorded instead of failing) and lets tests make commands fail. A parity suite
runs each scenario through the fixture and a hand-assembled runtime. The canonical example ships the reference tests.
Details: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: `saga-forge-test` → `saga-forge-core`, `saga-forge-runtime` (JDK only beyond them). Core and
runtime gain the command interceptor (JDK only). Test scope: JUnit 5, AssertJ, the runtime's test-jar (parity suite).

**Storage**: none in the fixture (in memory). `saga-forge-storage-postgres` gains a test-scope dependency on the
fixture for the durable-value contract test.

**Testing**: JUnit 5, AssertJ; parity suite against a hand-assembled runtime; example tests in `saga-forge-example`;
one container test in `saga-forge-storage-postgres`.

**Target Platform**: any JVM test framework that treats `AssertionError` as a failure.

**Project Type**: multi-module Maven library; one new module.

**Performance Goals**: SC-002 (three example tests < 1 s together), SC-005 (30 s of backoff tested in < 1 s).

**Constraints**: no Spring, database, transport or assertion library in the fixture's main dependencies (FR-001,
Constitution Principle I); the fixture adds no execution semantics of its own (FR-002).

**Scale/Scope**: one new module (~15 classes), one additive SPI in core, a small runtime change in
`EngineCommandGateway`, example and contract tests.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Result |
|---|---|---|
| I. Zero-Dependency Core | Core gains `CommandInterceptor`/`CommandInvocation` (JDK only). The fixture depends on core and runtime only; no Spring, storage or transport. | Pass |
| II. Annotation-Driven, Declarative Style | No annotation change; tests describe events and expectations, not orchestration code. The interceptor is a host plug-in like `HandlerParameterResolver`, not user-facing glue. | Pass |
| III. Formal Spec Conformance | No change to annotation semantics or validation rules. New Property 36 (command interception) in `.kiro/specs/saga-forge-framework/design.md`, added in the same change. | Pass (task) |
| IV. Explicit, Narrow Scope | No storage or transport added; the fixture's in-memory plug-ins are test infrastructure, not a supported storage or transport. | Pass |
| V. Documentation Hierarchy | README (testing section), `docs/architecture.md` (module and status), AGENTS.md updated with the module. | Pass (task) |

**Post-design re-check (after Phase 1)**: unchanged. The contracts keep the fixture free of Spring and storage; the only
core change is the additive interceptor. New gaps G-009-02 (duplicated in-memory plug-ins) and G-009-03 (the durable
value rule duplicated from Postgres, guarded by a contract test) are recorded in `.specify/memory/memory.md` with
G-009-01 (Spring helper deferred).

## Project Structure

### Documentation (this feature)

```text
specs/009-saga-test-fixture/
├── plan.md
├── research.md          # §1–§12, risks R1–R3
├── data-model.md        # fixture state, recorded commands, results, aggregate store
├── quickstart.md
├── contracts/
│   ├── fixture-api.md          # builder, given/when/then, reading state, messages, errors
│   ├── command-interceptor.md  # core SPI + runtime obligations, Property 36
│   └── context-value-rule.md   # durable SagaContext values, storage contract test
└── tasks.md
```

### Source Code (repository root)

```text
saga-forge-core/.../core/spi/CommandInterceptor.java, CommandInvocation.java      # new, additive
saga-forge-runtime/.../runtime/SagaRuntime.java                                   # Builder.commandInterceptor
saga-forge-runtime/.../runtime/internal/EngineCommandGateway.java, EngineSettings.java  # call the interceptor

saga-forge-test/                                   # new module, artifact saga-forge-test
└── src/main/java/io/github/gkosharovdev/sagaforge/test/
    ├── SagaFixture.java, Given.java, Then.java, EventResult.java, InstanceView.java, RecordedCommand.java
    ├── SagaExpectationError.java, UnsupportedContextValueException.java, FixtureClock.java, DurableContextValues.java
    └── (package-private) FixtureTransactions, FixtureStorage, FixtureContextStore, AggregateStore, LoopBackTransport,
                   FixtureCommandInterceptor, CommandFailureRule, FixtureObjects, FixtureLogger, Model, Messages
└── src/test/java/.../test/   fixtures/, FixtureApiTest, FailureAndCompensationTest, CorrelationTest,
                              HandlerModeTest, MessagesTest, ContextValueRuleTest, FixtureParityTest

saga-forge-example/src/test/.../OrderProcessingSagaFixtureTest.java              # FR-018 reference tests
saga-forge-storage-postgres/src/test/.../DurableContextValueContractTest.java    # research §8
```

**Structure Decision**: one new library module; additive SPI in core; the runtime calls it from the command gateway.
No change to the starter or the transports.

## Phases (for /speckit-tasks)

1. **Interceptor**: core SPI, runtime builder option and gateway calls, Property 36, runtime tests (default unchanged,
   exactly-once, failure handling, outbox and non-saga paths).
2. **Fixture core** (US1): module, plug-ins, loop-back transport, clock and ids, record-and-answer, `Then` basics.
3. **Failure, retry, compensation** (US2): failure rules, attempts, compensation expectations.
4. **Correlation and duplicates** (US3).
5. **Handler mode** (US4): registered handlers, aggregate store, outbox commands.
6. **Messages** (US6) and the context value rule (FR-019) with the Postgres contract test.
7. **Supplied instances** (US5), parity suite, example reference tests, docs, memory.

## Complexity Tracking

| Item | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| New core SPI `CommandInterceptor` | Record-and-answer (clarified default) needs commands without handlers not to fail; failure injection needs a hook before the handler (research §2) | A fake `CommandGateway` bypasses the runtime's command identities and unit of work, breaking FR-002 |
| Fixture-owned in-memory plug-ins duplicating the runtime's test doubles | The fixture is published; test-jar code is not, and the runtime cannot depend on the fixture (Maven cycle) | Publishing the runtime's test-jar exposes unsupported test utilities |
