# Quickstart: validating 016

**Prerequisites**: JDK 21+, Maven, Docker for the container scenarios. Run from the repository root.

## 1. Built-in models are unchanged (SC-003, FR-011)

```bash
mvn -B verify
```

Expected: the whole build passes with no test changed except those this feature adds; `ScannerErrorTextsTest` confirms
every built-in error text is byte-identical; `DomainAnnotationAccessTest` finds no direct domain-annotation read outside
the built-in resolver (SC-005).

## 2. The SPI over a third vocabulary (US4, SC-007)

```bash
mvn -B -pl saga-forge-runtime -am test -Dtest='ThirdVocabularyTest,VocabularyEquivalencePropertyTest'
```

Expected: the canonical saga runs over the test-only annotations (five annotation types) with the same step records,
outcome and outbox contents as the Saga Forge-annotated model (Property 48).

## 3. jMolecules markers and error texts (US1, US3, SC-004)

```bash
mvn -B -pl saga-forge-jmolecules -am test
```

Expected: every mapping row of [contracts/jmolecules-module.md](contracts/jmolecules-module.md) is recognised; each
role/identifier rule plus R30 and R31 fails with a text naming the jMolecules marker; the canonical saga runs over a
hand-assembled runtime.

## 4. Harvesting (US2, Property 49)

```bash
mvn -B -pl saga-forge-runtime,saga-forge-test -am test -Dtest='HarvestedEventsTest,HarvestPropertyTest,HarvestParityTest'
```

Expected: events taken from the aggregate are recorded after explicitly recorded ones, before the save; a rolled-back
attempt delivers none; the fixture matches the hand-assembled runtime.

## 5. Spring: auto-enable, 014 interplay (FR-024, FR-029, FR-036)

```bash
mvn -B -pl saga-forge-spring-boot-starter -am test -Dtest='SagaForgeDomainTypesConfigurationTest,HarvestWithSpringDataTest,JMoleculesInboundRouteTest,ReadmePropertiesTest'
```

Expected: jMolecules is recognised with the module on the class path and not with `saga-forge.jmolecules.enabled=false`;
an aggregate with both an event source and Spring Data `@DomainEvents` gives each saga step and each listener every event
once, in `in-transaction` mode and under Modulith interop; a jMolecules event published through
`ApplicationEventPublisher` starts a saga.

## 6. The jMolecules example end to end (SC-001, SC-002, SC-008)

```bash
mvn -B -Pcontainer-tests -pl saga-forge-example-jmolecules -am verify
```

Expected: `DomainDependsOnlyOnJMoleculesTest` passes (domain package depends on `java..` and `org.jmolecules..` only);
fixture tests run completion, stock-unavailable compensation and payment failure with real handlers and harvested
events; the Postgres container test runs the saga through the starter.

## 7. Release build and docs

```bash
mvn -B -P release -Dgpg.skip -DskipTests install
```

```bash
docs/guide/build-llms-full.sh && git diff --exit-code llms-full.txt
```

Expected: Javadoc builds for `saga-forge-jmolecules` and the new core types; `llms-full.txt` is in step with the guide.

## 8. Whole build with container suites

```bash
mvn -B -Pcontainer-tests verify
```

Expected: green; record the count in AGENTS.md "Current Status".
