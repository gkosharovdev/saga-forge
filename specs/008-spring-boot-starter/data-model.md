# Data Model: Spring Boot Starter

The starter adds no table. Its "data" is configuration, the collected model, and what the operator endpoint reports.

## Saga model (research §1)

| Source | What it contributes | Rule |
|---|---|---|
| Auto-configuration packages | `@Saga`, `@Aggregate`, classes with a `@CommandHandler` / `@AggregateLoader` / `@AggregateSaver` method | metadata scan, no class initialised |
| Application beans | the user class of each bean whose type carries one of those annotations | proxies resolved to the user class |
| `saga-forge.scan-packages` | as auto-configuration packages | list of package names |
| `SagaModelContributor` beans | `Collection<Class<?>>` each | for classes outside every scanned package |

The union, in a stable order (sagas first, then hosts, then aggregates, each by class name), is passed to
`SagaRuntime.builder().register(...)`. Events and commands are reached from signatures by `validateAll`.

## `SagaForgeProperties` (`saga-forge.*`, research §11)

| Property | Type | Default | Validation |
|---|---|---|---|
| `enabled` | boolean | `true` | — |
| `scan-packages` | list of package names | empty | each a valid Java package name |
| `storage.type` | `postgres` (later `mysql`, `mongodb`) | detected | must name a module on the classpath |
| `storage.postgres.initialize-schema` | `never` / `always` | `never` | enum |
| `runtime.max-total-retry-backoff` | duration | `30s` | positive |
| `application-events.mode` | `in-transaction` / `after-delivery` / `none` | `in-transaction` | enum |
| `transport.*` | 007 `TransportProperties` | 007 defaults | 007 rules |

Removed: 007's `saga-forge.transport.publish-application-events` (replaced by `application-events.mode`).

## Detection result (research §5)

| Situation | Outcome |
|---|---|
| exactly one storage module, or `storage.type` names one present | that module's `StorageProvider` and `SagaContextStore` beans |
| application-defined `StorageProvider` and `SagaContextStore` beans | those; detection skipped |
| no storage module and no application beans | `SagaForgeConfigurationException`: durable storage required, supported modules listed |
| several modules, no `storage.type` | `SagaForgeConfigurationException` naming the modules and the property |
| `storage.type` names an absent module | `SagaForgeConfigurationException` naming the property and the modules present |
| no broker transport class | in-memory transport (007) |

## `OutgoingMessages.recordedEvents` (core, research §8)

| Field | Rule |
|---|---|
| `recordedEvents` | every event recorded in the unit of work, in recording order; non-null, no null element; a superset of `events` |
| batch non-empty | at least one of `events`, `commands`, `recordedEvents` is non-empty |

## Operator endpoint payload (research §10)

```json
{
  "counts": { "UNCONSUMED": 3, "CONSUMED": 120, "SET_ASIDE": 1 },
  "oldestUnconsumed": "PT2.5S"
}
```

Counts are capped at 1,000 per status (`MessageStoreQueries.status()`); `oldestUnconsumed` is absent when nothing waits.
Health: `UP`, or `DOWN` with detail `setAside: <count>`.
