# Contract: model validation (`AnnotationScanner.validateAll`)

**Package**: `io.github.gkosharovdev.sagaforge.core.scanner` | Spec: FR-013, FR-014, FR-026, SC-001 | Rules:
research §7.

## `ValidationReport validateAll(Collection<Class<?>> classes)`

- **Pre-conditions**: `classes` non-null and contains no `null` element (`IllegalArgumentException` otherwise).
- **Effect**: validates every class in one pass and never stops at the first error.
  - Each `@Saga` class: all existing `validate(Class<?>)` rules plus the new saga-class rules.
  - Each other class declaring a `@CommandHandler`, `@AggregateLoader`, or `@AggregateSaver`: the handler,
    loader, and saver rules.
  - Each `@Aggregate`, `@DomainEvent`, and `@Command` type, whether passed in or reached from a step, handler,
    loader, or saver signature: the identifier rules.
  - Model rules across all classes: one handler per command type, one loader and one saver per aggregate
    type, and an `@AggregateIdentifier` on every command handled by an aggregate instance method.
- **Result**: `ValidationReport(classResults, modelErrors)`; `isValid()` is true only if every class result is
  valid and `modelErrors` is empty. Classes none of these rules apply to are ignored.

## New error messages

Messages follow the existing style in `ReflectiveAnnotationScanner`. Placeholders: `{class}` simple class name,
`{method}`, `{type}` simple type name.

| Rule | Message |
|---|---|
| Step returns a value | `@SagaStep method {method} in {class} must return void; dispatch commands through the CommandGateway instead` |
| Two steps on one event type | `@Saga class {class} has more than one @SagaStep reacting to {type}: {method}, {method}` |
| Handler on a saga | `@Saga class {class} must not declare @CommandHandler method {method}` |
| Several `CommandGateway` fields | `@Saga class {class} must declare at most one CommandGateway field, found {n}` |
| Handler command parameter count | `@CommandHandler method {method} in {class} must declare exactly one @Command parameter, found {n}` |
| Duplicate handler (model error) | `Command {type} has more than one @CommandHandler: {class}.{method}, {class}.{method}` |
| Aggregate or event identifier count | `{class} must declare exactly one @AggregateIdentifier field, found {n}` |
| Command identifier count | `@Command type {class} must declare at most one @AggregateIdentifier field, found {n}` |
| Command of an aggregate instance handler without identifier (model error) | `@Command type {class} is handled by @Aggregate instance method {class}.{method} and must declare an @AggregateIdentifier field` |
| Event aggregate not an aggregate | `@DomainEvent type {class} names aggregate {type}, which is not annotated @Aggregate` |
| Loader shape | `@AggregateLoader method {method} in {class} must take exactly one parameter and return an @Aggregate type` |
| Saver shape | `@AggregateSaver method {method} in {class} must take exactly one @Aggregate-typed parameter` |
| Duplicate loader or saver (model error) | `Aggregate {type} has more than one @AggregateLoader: {class}.{method}, {class}.{method}` (same wording for `@AggregateSaver`) |

`.kiro/specs/saga-forge-framework/design.md` gains one correctness property per new rule (Principle III), in
the format of its existing Property 1 and Property 9.
