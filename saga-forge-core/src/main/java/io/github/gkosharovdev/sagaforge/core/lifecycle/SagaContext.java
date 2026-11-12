package io.github.gkosharovdev.sagaforge.core.lifecycle;

import java.util.Optional;

/**
 * Step-execution contract giving a {@code @SagaStep} method typed read/write access to named
 * business-state values scoped to a single saga instance.
 *
 * <p>Values written by one step are visible to this and later steps of the same saga instance
 * (FR-001, FR-002). A named value is either a <em>scalar</em> (any non-structured value) or a
 * <em>structured</em> value whose individual fields can be updated independently via
 * {@link #updateStateAttribute(String, String, Object)} (FR-002a/b/c) as well as replaced wholesale via
 * {@link #putState(String, Object)}.</p>
 *
 * <p>Step-author code never constructs a {@code SagaContext} directly. It is reached through a single
 * {@code SagaContext}-typed field declared on the {@code @Saga} class, which the engine populates
 * (FR-008). This contract is distinct from {@link SagaLifecycle}, which governs instance-level
 * transitions and is consumed by engine code rather than step-author code.</p>
 *
 * <p>No production implementation ships in {@code saga-forge-core}; implementations are provided by
 * the engine runtime, the same posture as {@code StorageProvider} and {@code EventDispatcher}.</p>
 *
 * <h2>Scoping invariant (FR-010)</h2>
 * <p>A {@code SagaContext} instance is bound to exactly one saga instance for its entire lifetime. No
 * operation on it can read or write another saga instance's state, regardless of saga type: two
 * different instances writing under the same {@code name} never observe each other's values.</p>
 *
 * <h2>Representation</h2>
 * <p>Named values (structured values as nested {@code Map<String, Object>}) are persisted as their own
 * {@code SagaContextRecord}, through the {@code SagaContextStore} SPI, referencing the saga instance by
 * its saga id. They are <em>not</em> stored in {@code SagaInstance.state}, which keeps only the saga
 * class's own serialized fields (feature 002 FR-024, superseding feature 001's mapping onto
 * {@code SagaInstance.state}). Context values are managed independently of the saga-scoped unit of
 * work: values written by a step stay queryable by later steps whether that step's unit of work
 * committed or rolled back. A {@code SagaContext} does not call {@code SagaContextStore} itself; the
 * engine persists the values. The value semantics described here are unchanged.</p>
 *
 * <h2>Null-safety contracts</h2>
 * <ul>
 *   <li>{@link #getState(String, Class)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code name} is {@code null} or blank, or if
 *       {@code type} is {@code null}.</li>
 *   <li>{@link #putState(String, Object)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code name} is {@code null} or blank, or if
 *       {@code value} is {@code null}.</li>
 *   <li>{@link #updateStateAttribute(String, String, Object)} — implementations must throw
 *       {@link IllegalArgumentException} if {@code name} or {@code field} is {@code null} or blank,
 *       or if {@code value} is {@code null}.</li>
 * </ul>
 * <p>Because {@code null} values are never stored, {@link Optional#empty()} from
 * {@link #getState(String, Class)} always and only means "never written" (FR-004).</p>
 */
public interface SagaContext {

    /**
     * Reads the value stored under {@code name} as the requested {@code type}.
     *
     * <p>Returns a present {@link Optional} if a value is stored under {@code name} and is assignable to
     * {@code type}. Returns {@link Optional#empty()} if and only if no value was ever stored under
     * {@code name} for this saga instance (FR-004). Since {@code null} values are rejected on write,
     * there is no stored-{@code null} case to distinguish from "never written".</p>
     *
     * @param name the state value name; must not be {@code null} or blank
     * @param type the type the caller expects the stored value to be assignable to; must not be
     *             {@code null}
     * @param <T>  the expected value type
     * @return an {@link Optional} containing the stored value, or empty if nothing was ever stored
     *         under {@code name}
     * @throws SagaStateTypeMismatchException if a value exists under {@code name} but is not assignable
     *                                        to {@code type} (FR-003) — never an unchecked
     *                                        {@link ClassCastException} or a silently wrong value
     * @throws IllegalArgumentException       if {@code name} is {@code null} or blank, or
     *                                        {@code type} is {@code null}
     */
    <T> Optional<T> getState(String name, Class<T> type);

    /**
     * Creates a new value under {@code name}, or wholesale-replaces whatever was previously stored
     * there (FR-002).
     *
     * <p>The written value is visible to this and later steps of the same saga instance via
     * {@link #getState(String, Class)} and {@link #updateStateAttribute(String, String, Object)}.</p>
     *
     * @param name  the state value name; must not be {@code null} or blank
     * @param value the value to store; must not be {@code null}
     * @throws IllegalArgumentException if {@code name} is {@code null} or blank, or {@code value} is
     *                                  {@code null}
     */
    void putState(String name, Object value);

    /**
     * Mutates exactly one {@code field} within the structured value stored under {@code name},
     * leaving all of that value's other fields unchanged (FR-002a).
     *
     * <p>If no value exists yet under {@code name}, a new structured value containing only
     * {@code field} is created (FR-002b — upsert, not a failure). If a value already exists under
     * {@code name} but is a scalar rather than a structured value, the operation fails and the existing
     * value is left untouched, never partially overwritten (FR-002c).</p>
     *
     * @param name  the state value name; must not be {@code null} or blank
     * @param field the field within the structured value to set; must not be {@code null} or blank
     * @param value the field value to store; must not be {@code null}
     * @throws NonStructuredStateValueException if a value already exists under {@code name} but is a
     *                                          scalar (not structured) (FR-002c)
     * @throws IllegalArgumentException         if {@code name} or {@code field} is {@code null} or
     *                                          blank, or {@code value} is {@code null}
     */
    void updateStateAttribute(String name, String field, Object value);
}
