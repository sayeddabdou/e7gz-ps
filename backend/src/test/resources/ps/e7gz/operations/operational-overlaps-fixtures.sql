-- E7-30 OperationalOverlaps read: Maintenance, Venue Closure and Emergencies.
-- Run once per test JVM as the migrator (owner), outside any command, in ONE transaction: the deferred
-- tr_emergency_block_consistency_* triggers need each Emergency and its selling block to commit together.
-- Ids: e7300300-0000-4000-8000-0000000<kind><n>.
--
-- The database declares an Emergency at statement_timestamp() (guard_emergency_lifecycle) inside a materialized
-- Operating Session, so the Emergencies here are declared "now" with Expected End now() + 1 hour, in a session that
-- starts two hours before now. The tests read declared_at / expected_end_at back.

INSERT INTO geo.area (id, name)
VALUES ('e7300300-0000-4000-8000-000000000001', 'E7-30 operational overlaps area');

INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('e7300300-0000-4000-8000-000000000002', 'e7300300-0000-4000-8000-000000000001', 'E7-30 maintenance and closure venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7300300-0000-4000-8000-000000000003', 'e7300300-0000-4000-8000-000000000001', 'E7-30 other venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7300300-0000-4000-8000-000000000004', 'e7300300-0000-4000-8000-000000000001', 'E7-30 venue emergency venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7300300-0000-4000-8000-000000000005', 'e7300300-0000-4000-8000-000000000001', 'E7-30 resource emergency venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7300300-0000-4000-8000-000000000006', 'e7300300-0000-4000-8000-000000000001', 'E7-30 ended emergency venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789');

INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('e7300300-0000-4000-8000-000000000902', 'e7300300-0000-4000-8000-000000000002', 'PLAYSTATION'),
       ('e7300300-0000-4000-8000-000000000903', 'e7300300-0000-4000-8000-000000000003', 'PLAYSTATION'),
       ('e7300300-0000-4000-8000-000000000904', 'e7300300-0000-4000-8000-000000000004', 'PLAYSTATION'),
       ('e7300300-0000-4000-8000-000000000905', 'e7300300-0000-4000-8000-000000000005', 'PLAYSTATION'),
       ('e7300300-0000-4000-8000-000000000906', 'e7300300-0000-4000-8000-000000000006', 'PLAYSTATION');

INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state, operational_restriction)
VALUES ('e7300300-0000-4000-8000-000000000101', 'e7300300-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 maintained room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300300-0000-4000-8000-000000000102', 'e7300300-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 unmaintained room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300300-0000-4000-8000-000000000103', 'e7300300-0000-4000-8000-000000000003', 'PLAYSTATION', 'E7-30 other venue room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300300-0000-4000-8000-000000000104', 'e7300300-0000-4000-8000-000000000004', 'PLAYSTATION', 'E7-30 venue emergency room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300300-0000-4000-8000-000000000105', 'e7300300-0000-4000-8000-000000000005', 'PLAYSTATION', 'E7-30 failed room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300300-0000-4000-8000-000000000106', 'e7300300-0000-4000-8000-000000000005', 'PLAYSTATION', 'E7-30 working room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300300-0000-4000-8000-000000000107', 'e7300300-0000-4000-8000-000000000006', 'PLAYSTATION', 'E7-30 ended emergency room', 'PS5', 'ACTIVE', 'NONE');

-- Maintenance [2030-03-01 08:00, 09:00) UTC on the maintained room.
INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
VALUES ('e7300300-0000-4000-8000-000000000301', 'e7300300-0000-4000-8000-000000000101',
        tstzrange('2030-03-01 08:00:00+00', '2030-03-01 09:00:00+00', '[)'), 'E7-30 maintenance', false);

-- Closure [2030-03-02 08:00, 09:00) UTC of the maintenance and closure venue.
INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
VALUES ('e7300300-0000-4000-8000-000000000401', 'e7300300-0000-4000-8000-000000000002',
        tstzrange('2030-03-02 08:00:00+00', '2030-03-02 09:00:00+00', '[)'), 'PARTIAL', 'E7-30 closure');

-- An Operating Session around now for each Emergency venue (an Emergency is declared inside one).
INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open, default_local_close, updated_at)
VALUES ('e7300300-0000-4000-8000-000000000504', 'e7300300-0000-4000-8000-000000000004', TIME '12:00', TIME '23:00', now()),
       ('e7300300-0000-4000-8000-000000000505', 'e7300300-0000-4000-8000-000000000005', TIME '12:00', TIME '23:00', now()),
       ('e7300300-0000-4000-8000-000000000506', 'e7300300-0000-4000-8000-000000000006', TIME '12:00', TIME '23:00', now());

INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day, local_start, local_end,
                                     session_period, timezone, generated_from_version, generated_at)
SELECT v.session_id, v.venue_id, v.template_id, s.local_start::date, s.local_start, s.local_start + interval '22 hours',
       tstzrange(s.local_start AT TIME ZONE 'Africa/Cairo',
                 (s.local_start + interval '22 hours') AT TIME ZONE 'Africa/Cairo', '[)'),
       'Africa/Cairo', 0, now()
  FROM (VALUES ('e7300300-0000-4000-8000-000000000604'::uuid, 'e7300300-0000-4000-8000-000000000004'::uuid, 'e7300300-0000-4000-8000-000000000504'::uuid),
               ('e7300300-0000-4000-8000-000000000605'::uuid, 'e7300300-0000-4000-8000-000000000005'::uuid, 'e7300300-0000-4000-8000-000000000505'::uuid),
               ('e7300300-0000-4000-8000-000000000606'::uuid, 'e7300300-0000-4000-8000-000000000006'::uuid, 'e7300300-0000-4000-8000-000000000506'::uuid))
       AS v(session_id, venue_id, template_id)
 CROSS JOIN (SELECT date_trunc('hour', now() AT TIME ZONE 'Africa/Cairo') - interval '2 hours' AS local_start) s;

-- ACTIVE VENUE Emergency, declared now until now() + 1 hour, with its venue selling block.
-- declared_at, declared_period and the block's started_at are set by the database triggers.
INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at, expected_end_at,
                                  declared_period, reason)
VALUES ('e7300300-0000-4000-8000-000000000704', 'VENUE', 'e7300300-0000-4000-8000-000000000004', NULL, 'ACTIVE',
        now(), now() + interval '1 hour', tstzrange(now(), now() + interval '1 hour', '[)'), 'E7-30 venue emergency');
INSERT INTO venue.venue_selling_block (id, venue_id, cause, emergency_id, active, started_at)
VALUES ('e7300300-0000-4000-8000-000000000804', 'e7300300-0000-4000-8000-000000000004', 'EMERGENCY',
        'e7300300-0000-4000-8000-000000000704', true, now());

-- ACTIVE RESOURCE Emergency on the failed room only, with its resource selling block.
INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at, expected_end_at,
                                  declared_period, reason)
VALUES ('e7300300-0000-4000-8000-000000000705', 'RESOURCE', 'e7300300-0000-4000-8000-000000000005',
        'e7300300-0000-4000-8000-000000000105', 'ACTIVE',
        now(), now() + interval '1 hour', tstzrange(now(), now() + interval '1 hour', '[)'), 'E7-30 resource emergency');
INSERT INTO resource.resource_selling_block (id, resource_id, cause, emergency_id, active, started_at)
VALUES ('e7300300-0000-4000-8000-000000000805', 'e7300300-0000-4000-8000-000000000105', 'EMERGENCY',
        'e7300300-0000-4000-8000-000000000705', true, now());

-- A VENUE Emergency left ACTIVE here; OperationalOverlapsIntegrationTest ends it and closes its block in an app-role
-- command (v1.1.32 guard fix) before asserting that an ENDED Emergency no longer blocks.
INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at, expected_end_at,
                                  declared_period, reason)
VALUES ('e7300300-0000-4000-8000-000000000706', 'VENUE', 'e7300300-0000-4000-8000-000000000006', NULL, 'ACTIVE',
        now(), now() + interval '1 hour', tstzrange(now(), now() + interval '1 hour', '[)'), 'E7-30 ended emergency');
INSERT INTO venue.venue_selling_block (id, venue_id, cause, emergency_id, active, started_at)
VALUES ('e7300300-0000-4000-8000-000000000806', 'e7300300-0000-4000-8000-000000000006', 'EMERGENCY',
        'e7300300-0000-4000-8000-000000000706', true, now());
