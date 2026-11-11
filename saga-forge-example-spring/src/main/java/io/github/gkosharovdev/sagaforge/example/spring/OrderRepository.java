package io.github.gkosharovdev.sagaforge.example.spring;

import org.springframework.data.repository.CrudRepository;

/** A plain Spring Data repository; Saga Forge loads and saves {@link Order} through it. */
public interface OrderRepository extends CrudRepository<Order, String> {
}
