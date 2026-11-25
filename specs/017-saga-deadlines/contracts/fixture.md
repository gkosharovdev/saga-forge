# Contract: Saga test fixture (FR-018)

- The fixture's in-memory message store supports scheduling; the fixture's outbox supports it, so deadlines need no
  extra configuration.
- `SagaFixture.whenTimeElapses(Duration)` and `whenTimeReaches(Instant)` (also on `Given`, plus
  `Given.givenTimeElapses(Duration)` for history): advance `FixtureClock`, then deliver every message whose
  `notBefore <= now`, earliest due first (position order among equals), through the loop-back path (deadlines to `EventReceiver`, scheduled commands
  to `CommandReceiver`), until none is due. Returns an `EventResult` like `when().event(...)`.
- Advancing the clock directly (`fixture.clock().advance(...)`) delivers nothing until the next `whenTime...`;
  `when(event)` never delivers what is merely due. A time earlier than the clock, or a negative duration, is an
  `IllegalArgumentException`.
- `Then.expectOpenDeadline(String name)`, `expectNoOpenDeadline(String name)`, `expectDeadlineExpired(String name)`
  (the timeout step ran in this result), `expectDeadlineIgnored(String name)`.
- `SagaFixture.Builder.property(key, value)`: what a `${key[:default]}` duration resolves to (the runtime's
  `propertyResolver`); unset keys use their default.
- `InstanceView.openDeadlines()` → `Map<String, OpenDeadline>`.
- Record-and-answer mode: a scheduled command is recorded at delivery (`RecordedCommand` gains nothing new; its time is
  the clock at delivery).
- `FixtureParityTest` gains a deadline scenario compared with a hand-assembled runtime over the in-memory transport.
