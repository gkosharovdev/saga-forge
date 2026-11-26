# Quickstart: Validate Publish Once

## Prerequisites

JDK 21+, Maven, Docker (container suites).

## Fast checks (no containers)

```bash
mvn -pl saga-forge-core,saga-forge-runtime,saga-forge-transport-inmemory,saga-forge-test -am verify
```

Expect green, including `RecordedEventFanOutTest`, `EventRouterSourceTest`, the fan-out scenario of
`TransportAcceptanceSuite` and `FixtureParityTest`.

```bash
mvn -pl saga-forge-spring-boot-starter -am verify
```

Expect the starter unit tests (`InboundSagaEventListenerTest`, `PublishingGuardTest`, `AlreadyPublishedEventsTest`,
`ApplicationEventPublishingEventPublisherTest`) and `ReadmePropertiesTest` green.

## End to end (containers)

```bash
mvn -Pcontainer-tests -pl saga-forge-spring-boot-starter,saga-forge-example-spring -am verify
```

| Scenario | Test | Expected |
|---|---|---|
| Spring `publishEvent` in a transaction starts a saga; a module listener runs once | `PublishOnceContainerTest` | one instance, one step run, one listener call |
| Same, rolled back | `PublishOnceContainerTest` | nothing stored, nothing delivered |
| `EventPublisher.publish` reaches a module listener | `PublishOnceContainerTest` | one listener call, one step run |
| Handler saves a Spring Data aggregate with `@DomainEvents` | `InboundRouteInUnitOfWorkTest` | saga continues in process; listener once |
| Two saga types on one recorded event | `TransportAcceptanceSuite` (Postgres) | second type has one instance |
| `after-delivery` with the route on | `AfterDeliveryConflictTest` | startup fails naming both properties |
| Deadline model, Modulith registry, no interop property | `DeadlineInteropDefaultTest` | starts on the own log |
| Spring example places an order via `ApplicationEventPublisher` | example container test | saga completes |

## Whole build

```bash
mvn -Pcontainer-tests verify
```

```bash
mvn -P release -Dgpg.skip -DskipTests install
```

Then `docs/guide/build-llms-full.sh` and check `llms-full.txt` is unchanged afterwards in CI.
