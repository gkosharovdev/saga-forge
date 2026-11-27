-- 0.2.0 level: a COMPENSATION_FAILED instance and a consumer heartbeat (V4 drops the table).
INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at, last_modified_at, steps,
                           compensation_trigger)
    VALUES ('stuck-1', 'example.Saga', '{}', 'COMPENSATION_FAILED', now(), now(), '{}',
            '{"kind": "START_COMPENSATION", "stepName": "rejectOrder"}');
INSERT INTO saga_association (saga_id, association_property, association_value)
    VALUES ('stuck-1', 'orderId', 'o-stuck');
INSERT INTO saga_lifecycle_history (saga_id, sequence_number, kind, occurred_at)
    VALUES ('stuck-1', 1, 'COMPENSATION_STARTED', now());
INSERT INTO saga_message_store_consumer (consumer_id, host, started_at, last_seen_at)
    VALUES ('relay-1', 'host-1', now(), now());
