package io.github.gkosharovdev.sagaforge.storage.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Runs JDBC work in a short transaction of its own on a connection from a {@link DataSource}. */
final class OwnTransaction {

  /** JDBC work on an open connection. */
  @FunctionalInterface
  interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }

  private OwnTransaction() {}

  /**
   * Commits when {@code work} returns, rolls back when it throws.
   *
   * @param action what the work does, for the failure message ("could not &lt;action&gt;")
   * @return what {@code work} returned
   */
  static <T> T run(DataSource dataSource, String action, SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException | Error e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          e.addSuppressed(rollbackFailure);
        }
        throw e;
      } finally {
        try {
          connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
          // The connection is closed next; a broken one is discarded by the pool.
        }
      }
    } catch (SQLException e) {
      throw new UncheckedSqlException("could not " + action, e);
    }
  }
}
