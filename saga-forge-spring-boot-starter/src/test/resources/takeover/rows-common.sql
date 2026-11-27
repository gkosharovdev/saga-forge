-- Rows valid at every released level, for the takeover tests (specs/018 T028). Statements are separated by ';' at
-- line ends; nothing here is read by the runtime, only counted.
INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at, last_modified_at, steps, outcome)
    VALUES ('done-1', 'example.Saga', '{}', 'COMPLETED', now(), now(), '{}', 'completeOrder');
INSERT INTO saga_association (saga_id, association_property, association_value)
    VALUES ('done-1', 'orderId', 'o-done');
INSERT INTO saga_context (saga_id, values, last_modified_at) VALUES ('done-1', '{"orderAmount": "12.50"}', now());
INSERT INTO saga_lifecycle_history (saga_id, sequence_number, kind, step_name, occurred_at)
    VALUES ('done-1', 1, 'STARTED', 'handleOrderCreated', now());
INSERT INTO saga_lifecycle_history (saga_id, sequence_number, kind, outcome_step, occurred_at)
    VALUES ('done-1', 2, 'COMPLETED', 'completeOrder', now());
INSERT INTO saga_message_store (message_id, kind, payload_type, payload, recorded_at, status, next_attempt_at,
                                consumed_at)
    VALUES ('m-1', 'EVENT', 'example.OrderCreatedEvent', '{}', now(), 'CONSUMED', now(), now());
