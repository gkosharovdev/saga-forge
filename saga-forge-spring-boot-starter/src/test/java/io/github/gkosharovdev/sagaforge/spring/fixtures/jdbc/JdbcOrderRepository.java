package io.github.gkosharovdev.sagaforge.spring.fixtures.jdbc;

import org.springframework.data.repository.CrudRepository;

/** The order's Spring Data repository; Saga Forge finds it through {@code Repositories}. */
public interface JdbcOrderRepository extends CrudRepository<JdbcOrder, String> {
}
