package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that stops handing out connections after {@link #cut()}, as a crashed or
 * partitioned process would, or holds every request between {@link #stall()} and {@link
 * #unstall()}, as a frozen process would: its relay can neither renew its leadership nor deliver,
 * while the database itself keeps serving other instances (013 SC-002, SC-005).
 */
final class CuttableDataSource implements DataSource {

  private final DataSource delegate;
  private volatile boolean cut;
  private volatile CountDownLatch stalled = new CountDownLatch(0);

  CuttableDataSource(DataSource delegate) {
    this.delegate = delegate;
  }

  /** Every later {@link #getConnection()} fails. */
  void cut() {
    cut = true;
  }

  /** Every later {@link #getConnection()} waits until {@link #unstall()}. */
  void stall() {
    stalled = new CountDownLatch(1);
  }

  /** Lets the waiting and later {@link #getConnection()} calls proceed. */
  void unstall() {
    stalled.countDown();
  }

  private void check() throws SQLException {
    try {
      stalled.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SQLException("interrupted while stalled", interrupted);
    }
    if (cut) {
      throw new SQLException("connection refused (cut by the test)", "08001");
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    check();
    return delegate.getConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    check();
    return delegate.getConnection(username, password);
  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return delegate.getLogWriter();
  }

  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    delegate.setLogWriter(out);
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    delegate.setLoginTimeout(seconds);
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return delegate.getLoginTimeout();
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return delegate.getParentLogger();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return delegate.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return delegate.isWrapperFor(iface);
  }
}
