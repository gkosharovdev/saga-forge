package io.github.gkosharovdev.sagaforge.test;

import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;
import io.github.gkosharovdev.sagaforge.runtime.support.StorageProviderQueryContract;

/** The fixture's storage holds to the storage query contract. Its writes apply at once. */
class FixtureStorageQueryTest extends StorageProviderQueryContract {

  @Override
  protected StorageProvider newStorage() {
    return new FixtureStorage();
  }

  @Override
  protected void inTransaction(StorageProvider storage, Runnable work) {
    work.run();
  }
}
