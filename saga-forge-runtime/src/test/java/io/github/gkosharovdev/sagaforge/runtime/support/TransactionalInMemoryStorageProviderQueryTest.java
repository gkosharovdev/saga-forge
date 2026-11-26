package io.github.gkosharovdev.sagaforge.runtime.support;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;
import io.github.gkosharovdev.sagaforge.core.spi.StorageProvider;

class TransactionalInMemoryStorageProviderQueryTest extends StorageProviderQueryContract {

  private InMemoryTransactionBoundary boundary;

  @Override
  protected StorageProvider newStorage() {
    boundary = new InMemoryTransactionBoundary();
    return new TransactionalInMemoryStorageProvider(boundary);
  }

  @Override
  protected void inTransaction(StorageProvider storage, Runnable work) {
    LocalTransaction transaction = boundary.begin();
    work.run();
    transaction.commit();
  }
}
