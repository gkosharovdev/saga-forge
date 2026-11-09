# Contract: engine diagnostics

**Module**: `saga-forge-engine` | **Kind**: logging through `java.lang.System.Logger`; no Saga Forge API | Spec:
FR-038; research §10.

## Routing

- Default logger: `System.getLogger("io.github.gkosharovdev.sagaforge.engine")`. A host redirects every diagnostic
  without changing the engine by installing a `System.LoggerFinder` (for example SLF4J 2's
  `slf4j-jdk-platform-logging`), or by passing its own `System.Logger` to `SagaEngine.builder().logger(...)`.
- Messages are built lazily (`Supplier<String>`) for levels below `INFO`.

## Messages

Every message starts with a fixed event name, then `key=value` pairs; keys are omitted when unknown. `event` is the
domain event's fully qualified class name, `sagaType` the saga's, `sagaId`, `uow` the unit-of-work id.

| Event name | Level | Keys | When |
|---|---|---|---|
| `saga.startup.refused` | `ERROR` | `errors` (count), then one `ERROR` line per error | `start()` refused |
| `saga.startup.started` | `INFO` | `sagaTypes` | `start()` succeeded |
| `saga.event.ignored` | `DEBUG` | `event` | no saga reacts |
| `saga.event.unmatched` | `DEBUG` | `event`, `sagaType`, `step` | no instance correlates |
| `saga.event.unexpected` | `INFO` | `event`, `sagaType`, `sagaId`, `step` | only terminal instances |
| `saga.event.duplicateStart` | `INFO` | `event`, `sagaType`, `associationProperty`, `associationValue` | duplicate start |
| `saga.uow.committed` | `DEBUG` | `event`, `sagaType`, `sagaId`, `uow`, `outcome` (`WAITING`/`COMPLETED`), `steps` | commit |
| `saga.uow.rolledBack` | `WARNING` | `event`, `sagaType`, `sagaId`, `uow`, `step`, `cause` (type and message); the throwable attached | rollback |
| `saga.instance.failed` | `WARNING` | `sagaType`, `sagaId`, `step`, `cause` | `FAILED` write committed |
| `saga.instance.failedNotRecorded` | `ERROR` | `sagaType`, `sagaId`, `cause`; throwable attached | `FAILED` write failed, or the instance was already terminal |
| `saga.uow.conflict` | `WARNING` | `event`, `sagaType`, `sagaId`, `uow` | write-time conflict (`NOT_HANDLED`) |
| `saga.uow.notStarted` | `INFO` | `event`, `sagaType`, `sagaId` | instance still `CREATED` (`NOT_HANDLED`) |
| `saga.handoff.failed` | `ERROR` | `uow`, `sagaType`, `sagaId`, `events` (count); throwable attached | `AFTER_COMMIT` handoff threw |
| `saga.event.notHandled` | `ERROR` | `event`; throwable attached | `EventHandlingException` raised |

Tests assert on event names and keys through a recording `System.Logger`, not on full text.
