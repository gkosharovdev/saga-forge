/**
 * Framework-owned failure classification ({@code specs/005-step-retry}).
 *
 * <ul>
 *   <li>{@link TransientFailure} and {@link FinalFailure} — marker interfaces an exception class implements to
 *       tell the engine whether running the step again may help. No base class is required, so host code keeps
 *       its own exception hierarchy and needs no Spring, retry-library or transport type.</li>
 *   <li>{@link FailureClassification} — the result the engine records on a failed saga instance and on each
 *       retried attempt.</li>
 * </ul>
 *
 * <p>Like the rest of {@code saga-forge-core}, this package has zero dependencies.</p>
 */
package io.github.gkosharovdev.sagaforge.core.failure;
