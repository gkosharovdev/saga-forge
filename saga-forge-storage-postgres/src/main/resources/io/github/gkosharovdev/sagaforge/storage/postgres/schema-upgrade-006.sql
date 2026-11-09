-- Saga Forge PostgreSQL schema upgrade for specs/006-saga-compensation (contracts/storage-delta.md).
-- Applies to a database created from (or upgraded to) the schema.sql of specs/005. Applied by the host application;
-- this module ships no migration tool. Run it once, in one transaction, while no engine is running; running it again
-- changes nothing.

ALTER TABLE saga_instance ADD COLUMN IF NOT EXISTS compensation_trigger JSONB;

ALTER TABLE saga_instance DROP CONSTRAINT IF EXISTS trigger_iff_compensation;

ALTER TABLE saga_instance ADD CONSTRAINT trigger_iff_compensation
    CHECK ((status IN ('COMPENSATING', 'COMPENSATED', 'REQUIRES_INTERVENTION'))
        = (compensation_trigger IS NOT NULL));

ALTER TABLE saga_lifecycle_history ADD COLUMN IF NOT EXISTS compensation_trigger JSONB;

-- Completed steps gain "sequence", their position in the instance's completion order: the order of their first
-- STEP_COMPLETED history row, then, for completed steps without one, step-name order (memory.md G-006-07). Steps
-- that already have a sequence are left as they are.
UPDATE saga_instance i
   SET steps = (
       SELECT jsonb_object_agg(s.key,
                  CASE WHEN s.value ->> 'status' = 'COMPLETED' AND (s.value -> 'sequence') IS NULL
                       THEN s.value || jsonb_build_object('sequence', o.seq)
                       ELSE s.value END)
         FROM jsonb_each(i.steps) AS s(key, value)
         LEFT JOIN (
              SELECT c.key AS step_name,
                     ROW_NUMBER() OVER (ORDER BY h.first_sequence NULLS LAST, c.key) AS seq
                FROM jsonb_each(i.steps) AS c(key, value)
                LEFT JOIN (SELECT step_name, MIN(sequence_number) AS first_sequence
                             FROM saga_lifecycle_history
                            WHERE saga_id = i.saga_id AND kind = 'STEP_COMPLETED'
                            GROUP BY step_name) h ON h.step_name = c.key
               WHERE c.value ->> 'status' = 'COMPLETED') o ON o.step_name = s.key)
 WHERE EXISTS (SELECT 1
                 FROM jsonb_each(i.steps) AS e(key, value)
                WHERE e.value ->> 'status' = 'COMPLETED' AND (e.value -> 'sequence') IS NULL);
