-- 0.4.0 level: a COMPENSATION_FAILED instance, a scheduled message and an expired relay lease.
INSERT INTO saga_instance (saga_id, saga_type, state, status, created_at, last_modified_at, steps,
                           compensation_trigger)
    VALUES ('stuck-1', 'example.Saga', '{}', 'COMPENSATION_FAILED', now(), now(), '{}',
            '{"kind": "START_COMPENSATION", "stepName": "rejectOrder"}');
INSERT INTO saga_association (saga_id, association_property, association_value)
    VALUES ('stuck-1', 'orderId', 'o-stuck');
INSERT INTO saga_lifecycle_history (saga_id, sequence_number, kind, occurred_at)
    VALUES ('stuck-1', 1, 'COMPENSATION_STARTED', now());
INSERT INTO saga_message_store (message_id, kind, payload_type, payload, recorded_at, status, next_attempt_at,
                                consumed_at, not_before)
    VALUES ('m-2', 'EVENT', 'example.Deadline', '{}', now(), 'CANCELLED', now(), now(), now());
INSERT INTO saga_message_store_leader (id, holder_id, holder_host, fencing_token, acquired_at, expires_at)
    VALUES (1, 'relay-1', 'host-1', 3, now() - interval '1 hour', now() - interval '1 hour');
