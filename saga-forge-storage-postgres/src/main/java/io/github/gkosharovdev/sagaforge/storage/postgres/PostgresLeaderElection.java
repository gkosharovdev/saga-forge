package io.github.gkosharovdev.sagaforge.storage.postgres;

import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderElection;
import io.github.gkosharovdev.sagaforge.core.spi.store.LeaderLease;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * {@link LeaderElection} over the one-row table {@code saga_message_store_leader} ({@code
 * specs/013-active-standby-relay} data-model.md "Postgres", contracts/leader-election-spi.md).
 *
 * <p>Each call is one statement in a short transaction of its own; the row lock of that statement
 * serializes concurrent candidates (C11). The row is never deleted, so the fencing token only
 * grows. Times are the caller's ({@code now}), never the database's clock (research R3). SQL
 * failures propagate as {@link UncheckedSqlException}. Thread-safe.
 */
final class PostgresLeaderElection implements LeaderElection {

  private static final String RETURNED =
      " RETURNING holder_id, holder_host, fencing_token, acquired_at, expires_at";

  private final DataSource dataSource;

  PostgresLeaderElection(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public Optional<LeaderLease> tryAcquire(
      String candidateId, String host, Instant now, Duration leaseDuration) {
    String sql =
        "INSERT INTO saga_message_store_leader"
            + " (id, holder_id, holder_host, fencing_token, acquired_at, expires_at)"
            + " VALUES (1, ?, ?, 1, ?, ?)"
            + " ON CONFLICT (id) DO UPDATE SET holder_id = EXCLUDED.holder_id,"
            + " holder_host = EXCLUDED.holder_host,"
            + " fencing_token = saga_message_store_leader.fencing_token + 1,"
            + " acquired_at = EXCLUDED.acquired_at, expires_at = EXCLUDED.expires_at"
            + " WHERE saga_message_store_leader.expires_at <= ?"
            + RETURNED;
    return OwnTransaction.run(
        dataSource,
        "acquire the leadership for " + candidateId,
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, candidateId);
            statement.setString(2, host);
            statement.setObject(3, utc(now));
            statement.setObject(4, utc(now.plus(leaseDuration)));
            statement.setObject(5, utc(now));
            return single(statement);
          }
        });
  }

  @Override
  public Optional<LeaderLease> renew(LeaderLease lease, Instant now, Duration leaseDuration) {
    String sql =
        "UPDATE saga_message_store_leader SET expires_at = ?"
            + " WHERE id = 1 AND fencing_token = ? AND expires_at > ?"
            + RETURNED;
    return OwnTransaction.run(
        dataSource,
        "renew the leadership of " + lease.holderId(),
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, utc(now.plus(leaseDuration)));
            statement.setLong(2, lease.fencingToken());
            statement.setObject(3, utc(now));
            return single(statement);
          }
        });
  }

  @Override
  public void release(LeaderLease lease, Instant now) {
    OwnTransaction.run(
        dataSource,
        "release the leadership of " + lease.holderId(),
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE saga_message_store_leader"
                      + " SET expires_at = LEAST(expires_at, GREATEST(?, acquired_at))"
                      + " WHERE id = 1 AND fencing_token = ?")) {
            statement.setObject(1, utc(now));
            statement.setLong(2, lease.fencingToken());
            statement.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public Optional<LeaderLease> current() {
    return OwnTransaction.run(
        dataSource,
        "read the leadership",
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT holder_id, holder_host, fencing_token, acquired_at, expires_at"
                      + " FROM saga_message_store_leader WHERE id = 1")) {
            return single(statement);
          }
        });
  }

  /**
   * Holds the leadership row with {@code FOR SHARE} for the rest of {@code connection}'s
   * transaction, if {@code lease} is still current at {@code now}: a takeover then waits until that
   * transaction ends (C12).
   *
   * @return whether {@code lease} is current
   */
  static boolean holdIfCurrent(Connection connection, LeaderLease lease, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM saga_message_store_leader"
                + " WHERE id = 1 AND fencing_token = ? AND expires_at > ? FOR SHARE")) {
      statement.setLong(1, lease.fencingToken());
      statement.setObject(2, utc(now));
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next();
      }
    }
  }

  private static Optional<LeaderLease> single(PreparedStatement statement) throws SQLException {
    try (ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        return Optional.empty();
      }
      return Optional.of(
          new LeaderLease(
              resultSet.getString("holder_id"),
              resultSet.getString("holder_host"),
              resultSet.getLong("fencing_token"),
              resultSet.getObject("acquired_at", OffsetDateTime.class).toInstant(),
              resultSet.getObject("expires_at", OffsetDateTime.class).toInstant()));
    }
  }

  private static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
