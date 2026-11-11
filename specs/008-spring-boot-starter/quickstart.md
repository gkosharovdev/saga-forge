# Quickstart: validating the Spring Boot starter

## Commands

```bash
mvn -B -pl saga-forge-spring-boot-starter,saga-forge-example-spring -am clean verify
```

```bash
mvn -B -Pcontainer-tests -pl saga-forge-spring-boot-starter,saga-forge-example-spring -am clean verify
```

## Scenarios

| # | Scenario | Expected | Covers |
|---|---|---|---|
| 1 | `saga-forge-example-spring` with only dependencies and `application.properties` | Order completes; refund on stock shortage ends `COMPENSATED`; no assembly code in the module | US1, SC-001, SC-002 |
| 2 | Saga bean with constructor-injected service; handler parameter bean with `@Qualifier` | Injected and used; none/several beans fail startup naming the parameter | US2 |
| 3 | `Order` saved through a Spring Data JDBC repository, no `@AggregateLoader` | Loaded and saved in the unit of work's transaction | US2, FR-013 |
| 4 | No storage module / two storage modules / bad `storage.type` | Startup fails; failure analysis names modules and the property | US3, FR-007 |
| 5 | Invalid saga model | Startup fails with the full report through failure analysis | US3, FR-017 |
| 6 | Singleton saga bean; handler class without a bean | Startup fails naming the bean or class | edge cases |
| 7 | Application defines each of the nine replaceable beans | Its bean is used (one test each) | US4, SC-004 |
| 8 | `initialize-schema=always` on an empty database / `never` on an empty database | Schema created / startup fails naming tables and resource | FR-016 |
| 9 | Spring Modulith three-module app | `ApplicationModules.verify()` passes; an `@ApplicationModuleListener` in another module receives a saga event once, after commit, never on rollback | US5, FR-018a, FR-019 |
| 10 | Actuator present, one set-aside message | Endpoint reports counts; health `DOWN` with `setAside: 1`; no write operation | US6, FR-020 |
| 11 | 007 `TransportAcceptanceSuite` with the runtime assembled by the starter, own log and Modulith interop | Passes unchanged | SC-006 |
| 12 | 20-saga model startup | ≤ 1 s slower than without sagas | SC-005 |
