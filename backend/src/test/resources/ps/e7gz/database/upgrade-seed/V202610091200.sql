-- Upgrade-path seed, loaded right after V202610091200 (scheduled_job outcomes and correlation ID): jobs that carry their
-- creating command's correlation ID, one moved later and one superseded through the new job_finish, and a frequent
-- system job's one row moved to its next run, so every later migration also runs over them.

INSERT INTO technical.scheduled_job (id, type, subject_id, subject_type, due_at, status, idempotency_key, correlation_id)
VALUES ('5afe0000-0000-4000-8000-000000000106', 'SEED_JOB', '5afe0000-0000-4000-8000-000000000081', 'BOOKING',
        '2026-01-01 10:00:00+00', 'PENDING', 'SEED_JOB:moved-later', '5afe0000-0000-4000-8000-000000000107'),
       ('5afe0000-0000-4000-8000-000000000108', 'SEED_JOB', '5afe0000-0000-4000-8000-000000000081', 'BOOKING',
        '2026-01-01 10:00:00+00', 'PENDING', 'SEED_JOB:superseded-by-worker', '5afe0000-0000-4000-8000-000000000107');
SELECT technical.job_lease('5afe0000-0000-4000-8000-000000000106', 'seed-worker', interval '1 hour');
SELECT technical.job_finish('5afe0000-0000-4000-8000-000000000106', 'seed-worker', 'PENDING', 'seed back-off',
                            '2031-03-01 10:00:00+00');
SELECT technical.job_lease('5afe0000-0000-4000-8000-000000000108', 'seed-worker', interval '1 hour');
SELECT technical.job_finish('5afe0000-0000-4000-8000-000000000108', 'seed-worker', 'SUPERSEDED');

INSERT INTO technical.scheduled_job (id, type, subject_id, subject_type, due_at, status, idempotency_key, correlation_id)
VALUES ('5afe0000-0000-4000-8000-000000000109', 'SEED_EVERY', '5afe0000-0000-4000-8000-000000000110', 'SYSTEM',
        '2026-01-01 10:00:00+00', 'PENDING', 'SEED_EVERY:EVERY', '5afe0000-0000-4000-8000-000000000109');
SELECT technical.job_lease('5afe0000-0000-4000-8000-000000000109', 'seed-worker', interval '1 hour');
SELECT technical.job_finish('5afe0000-0000-4000-8000-000000000109', 'seed-worker', 'PENDING', NULL,
                            '2026-01-01 10:01:00+00', true);
