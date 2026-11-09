package io.github.gkosharovdev.sagaforge.storage.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;

/**
 * {@link JdbcTransactionBoundary} over {@link JdbcFake} (specs/004-saga-engine tasks.md T061,
 * contracts/transaction-boundary.md "JdbcTransactionBoundary").
 */
class JdbcTransactionBoundaryTest {

    private final JdbcFake fake = new JdbcFake();
    private final JdbcTransactionBoundary boundary = new JdbcTransactionBoundary(fake.dataSource());

    @Test
    @DisplayName("begin takes one connection, sets autoCommit = false and binds the transaction")
    void beginBinds() {
        LocalTransaction transaction = boundary.begin();

        assertThat(fake.onlyConnection().calls()).containsExactly("setAutoCommit(false)");
        assertThat(boundary.current()).containsSame(transaction);
        assertThat(boundary.isActive()).isTrue();
        transaction.rollback();
    }

    @Test
    @DisplayName("resource(Connection.class) is the bound connection; any other type is empty")
    void resources() throws SQLException {
        LocalTransaction transaction = boundary.begin();

        Connection connection = transaction.resource(Connection.class).orElseThrow();
        assertThat(connection.isClosed()).isFalse();
        assertThat(transaction.resource(String.class)).isEmpty();
        assertThatThrownBy(() -> transaction.resource(null)).isInstanceOf(IllegalArgumentException.class);
        transaction.rollback();
    }

    @Test
    @DisplayName("commit commits, restores autoCommit, closes and unbinds")
    void commitEnds() {
        boundary.begin().commit();

        assertThat(fake.onlyConnection().calls())
                .containsExactly("setAutoCommit(false)", "commit", "setAutoCommit(true)", "close");
        assertThat(boundary.current()).isEmpty();
    }

    @Test
    @DisplayName("rollback rolls back, restores autoCommit, closes and unbinds")
    void rollbackEnds() {
        boundary.begin().rollback();

        assertThat(fake.onlyConnection().calls())
                .containsExactly("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close");
        assertThat(boundary.current()).isEmpty();
    }

    @Test
    @DisplayName("a second begin on the same thread throws and takes no connection")
    void secondBeginThrows() {
        LocalTransaction transaction = boundary.begin();

        assertThatThrownBy(boundary::begin).isInstanceOf(IllegalStateException.class);
        assertThat(fake.connections()).hasSize(1);
        transaction.rollback();
    }

    @Test
    @DisplayName("a transaction is bound to its thread only")
    void boundToThread() throws Exception {
        LocalTransaction transaction = boundary.begin();

        boolean visibleElsewhere = CompletableFuture.supplyAsync(boundary::isActive).get(5, TimeUnit.SECONDS);

        assertThat(visibleElsewhere).isFalse();
        transaction.rollback();
    }

    @Test
    @DisplayName("commit on a rollback-only transaction rolls back and throws")
    void rollbackOnlyCommit() {
        LocalTransaction transaction = boundary.begin();
        transaction.setRollbackOnly();

        assertThat(transaction.isRollbackOnly()).isTrue();
        assertThatThrownBy(transaction::commit).isInstanceOf(IllegalStateException.class);
        assertThat(fake.onlyConnection().calls())
                .containsExactly("setAutoCommit(false)", "rollback", "setAutoCommit(true)", "close");
        assertThat(boundary.current()).isEmpty();
    }

    @Test
    @DisplayName("an ended transaction rejects commit, rollback, setRollbackOnly and resource")
    void endedTransactionRejects() {
        LocalTransaction transaction = boundary.begin();
        transaction.commit();

        assertThatThrownBy(transaction::commit).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(transaction::rollback).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(transaction::setRollbackOnly).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.resource(Connection.class)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a failing commit is wrapped; it is rolled back, the connection closed and the transaction unbound")
    void failingCommit() {
        LocalTransaction transaction = boundary.begin();
        SQLException failure = new SQLException("commit failed");
        fake.failNextCommit(failure);

        assertThatThrownBy(transaction::commit).isInstanceOf(UncheckedSqlException.class).hasCause(failure);
        assertThat(fake.onlyConnection().calls())
                .containsExactly("setAutoCommit(false)", "commit", "rollback", "setAutoCommit(true)", "close");
        assertThat(fake.onlyConnection().isClosed()).isTrue();
        assertThat(boundary.current()).isEmpty();
    }

    @Test
    @DisplayName("a null DataSource is rejected")
    void nullDataSource() {
        assertThatThrownBy(() -> new JdbcTransactionBoundary(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
