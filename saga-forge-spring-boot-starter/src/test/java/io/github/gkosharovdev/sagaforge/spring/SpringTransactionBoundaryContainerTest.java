package io.github.gkosharovdev.sagaforge.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.gkosharovdev.sagaforge.core.spi.LocalTransaction;

/** {@link SpringTransactionBoundary} over a real database (tasks.md T068). */
@Tag("container")
class SpringTransactionBoundaryContainerTest {

    private final DataSource dataSource = StarterPostgres.dataSource();
    private final DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
    private final SpringTransactionBoundary boundary = new SpringTransactionBoundary(manager, dataSource);
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    @BeforeEach
    void reset() {
        StarterPostgres.reset();
        jdbc.execute("CREATE TABLE IF NOT EXISTS boundary_probe (v TEXT)");
        jdbc.execute("TRUNCATE boundary_probe");
    }

    private void insert(LocalTransaction tx, String value) throws Exception {
        Connection connection = tx.resource(Connection.class).orElseThrow();
        try (var statement = connection.prepareStatement("INSERT INTO boundary_probe VALUES (?)")) {
            statement.setString(1, value);
            statement.executeUpdate();
        }
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM boundary_probe", Integer.class);
    }

    @Test
    @DisplayName("begin/commit writes through the bound connection; rollback discards; current() tracks the thread")
    void commitAndRollback() throws Exception {
        assertThat(boundary.current()).isEmpty();
        LocalTransaction committed = boundary.begin();
        assertThat(boundary.current()).containsSame(committed);
        insert(committed, "a");
        committed.commit();
        assertThat(boundary.current()).isEmpty();

        LocalTransaction rolledBack = boundary.begin();
        insert(rolledBack, "b");
        rolledBack.setRollbackOnly();
        assertThat(rolledBack.isRollbackOnly()).isTrue();
        rolledBack.rollback();

        assertThat(rows()).isEqualTo(1);
        assertThatThrownBy(committed::commit).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("begin joins a host transaction: its writes commit or roll back with the host's")
    void joinsHostTransaction() {
        TransactionTemplate host = new TransactionTemplate(manager);
        host.executeWithoutResult(status -> {
            LocalTransaction joined = boundary.begin();
            try {
                insert(joined, "joined");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            joined.commit();
            status.setRollbackOnly();
        });

        assertThat(rows()).isZero();
    }
}
