package io.github.gkosharovdev.sagaforge.spring.fixtures.harvest;

import org.springframework.data.keyvalue.repository.KeyValueRepository;

/**
 * A Spring Data map repository; the starter's {@code SpringDataAggregateResolver} uses it. It
 * extends {@code KeyValueRepository} so Spring Data assigns it to the map module beside Spring Data
 * JDBC on the test class path.
 */
public interface ShipmentRepository extends KeyValueRepository<Shipment, String> {}
