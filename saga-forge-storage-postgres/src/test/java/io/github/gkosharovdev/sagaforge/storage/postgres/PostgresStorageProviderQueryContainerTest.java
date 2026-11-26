package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.runtime.support.StorageProviderQueryContract;
import org.junit.jupiter.api.Tag;

/** {@link PostgresStorageProvider} holds to the storage query contract. */
@Tag("container")
class PostgresStorageProviderQueryContainerTest extends StorageProviderQueryContract {

  @Override
  protected StorageProvider newStorage() {
    PostgresTestSupport.reset();
    return new PostgresStorageProvider(PostgresTestSupport.newDataSource());
  }

  @Override
  protected void inTransaction(StorageProvider storage, Runnable work) {
    work.run();
  }
}
