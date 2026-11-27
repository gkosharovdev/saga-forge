package io.github.gkosharovdev.sagaforge.spring.storage;

import io.github.gkosharovdev.sagaforge.storage.postgres.SchemaVersion;

/**
 * Applies Saga Forge's missing schema versions under Saga Forge's own history ({@code
 * initialize-schema=migrate}). {@link FlywaySchemaMigrator} is the implementation; a test double
 * stands in for it in {@code SchemaInitializerTest}.
 */
interface SchemaMigrator {

  /**
   * @param baseline the version an existing database without history is at, recorded as the
   *     baseline before later versions are applied; {@code null} when the history table exists or
   *     the database has none of Saga Forge's tables
   * @return the newest version applied once this returns
   */
  SchemaVersion migrate(SchemaVersion baseline);
}
