-- E7-30 isFree end to end: owner rows for each of the eight checks, read through the real owner-module adapters.
-- Run once per test JVM as the migrator (owner), outside any command, in ONE transaction: the deferred
-- tr_emergency_block_consistency_* triggers need each Emergency and its selling block to commit together.
-- Ids: e7300000-0000-4000-8000-0000000<kind><n>.
--
-- The tests read selling blocks at the command instant (today), so the plain blocks below start in 2020. The database
-- declares an Emergency at statement_timestamp() inside a materialized Operating Session, so the two Emergencies are
-- declared "now" with Expected End now() + 1 hour; the tests read declared_at / expected_end_at back.

INSERT INTO geo.area (id, name)
VALUES ('e7300000-0000-4000-8000-000000000001', 'E7-30 availability area');

-- Main ACTIVE Venue (a Manual Booking and a Tournament both refuse an ONBOARDING Venue), its activity and account.
INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('e7300000-0000-4000-8000-000000000002', 'e7300000-0000-4000-8000-000000000001', 'E7-30 availability venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ACTIVE', 'Africa/Cairo', '+20223456789');

INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('e7300000-0000-4000-8000-000000000003', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION');

INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status, language_preference)
VALUES ('e7300000-0000-4000-8000-000000000004', 'e7300000-0000-4000-8000-000000000002', 'e7-30-availability-venue',
        'x', 'ENABLED', 'en');

-- Operating Session a Tournament owner must reference (1 February 2030, 12:00-23:00 Cairo).
INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open, default_local_close, updated_at)
VALUES ('e7300000-0000-4000-8000-000000000005', 'e7300000-0000-4000-8000-000000000002', TIME '12:00', TIME '23:00', now());

INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day, local_start, local_end,
                                     session_period, timezone, generated_from_version, generated_at)
VALUES ('e7300000-0000-4000-8000-000000000006', 'e7300000-0000-4000-8000-000000000002',
        'e7300000-0000-4000-8000-000000000005', DATE '2030-02-01',
        TIMESTAMP '2030-02-01 12:00', TIMESTAMP '2030-02-01 23:00',
        tstzrange(TIMESTAMP '2030-02-01 12:00' AT TIME ZONE 'Africa/Cairo',
                  TIMESTAMP '2030-02-01 23:00' AT TIME ZONE 'Africa/Cairo', '[)'),
        'Africa/Cairo', 0, now());

-- Resources of the main Venue, one per scenario.
INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state, operational_restriction)
VALUES ('e7300000-0000-4000-8000-000000000101', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 clear room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300000-0000-4000-8000-000000000102', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 disabled review room', 'PS5', 'DISABLED', 'OWNER_REVIEW_REQUIRED'),
       ('e7300000-0000-4000-8000-000000000103', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 archived room', 'PS5', 'ARCHIVED', 'NONE'),
       ('e7300000-0000-4000-8000-000000000104', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 owner review room', 'PS5', 'ACTIVE', 'OWNER_REVIEW_REQUIRED'),
       ('e7300000-0000-4000-8000-000000000105', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 blocked room', 'PS5', 'DISABLED', 'OWNER_REVIEW_REQUIRED'),
       ('e7300000-0000-4000-8000-000000000106', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 maintained room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300000-0000-4000-8000-000000000107', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 closure room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300000-0000-4000-8000-000000000108', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 no-show room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300000-0000-4000-8000-000000000109', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 holds room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300000-0000-4000-8000-00000000010a', 'e7300000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 booked room', 'PS5', 'ACTIVE', 'NONE');

-- Check 2: the blocked room has an active plain selling block since 2020 (and is also DISABLED + owner review).
INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at)
VALUES ('e7300000-0000-4000-8000-000000000205', 'e7300000-0000-4000-8000-000000000105', 'E7-30 test block', true,
        '2020-01-01 00:00:00+00');

-- Check 6: Maintenance [2030-03-01 08:00, 09:00) UTC on the maintained room.
INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
VALUES ('e7300000-0000-4000-8000-000000000301', 'e7300000-0000-4000-8000-000000000106',
        tstzrange('2030-03-01 08:00:00+00', '2030-03-01 09:00:00+00', '[)'), 'E7-30 availability maintenance', false);

-- Check 7: Closure [2030-03-02 08:00, 09:00) UTC of the main Venue.
INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
VALUES ('e7300000-0000-4000-8000-000000000401', 'e7300000-0000-4000-8000-000000000002',
        tstzrange('2030-03-02 08:00:00+00', '2030-03-02 09:00:00+00', '[)'), 'PARTIAL', 'E7-30 availability closure');

-- Owner of every BOOKING / LATE_CONFIRM_HOLD row in the tests: a Manual Booking, CONFIRMED, out of hours.
INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity, console_type, play_mode,
                             resource_name_snapshot, start_at, end_at, nominal_booked_minutes, hourly_price_snapshot,
                             venue_price_total, booking_fee_snapshot, total_amount, business_date, session_timezone,
                             created_at)
VALUES ('e7300000-0000-4000-8000-000000000201', 'e7300000-0000-4000-8000-000000000002',
        'e7300000-0000-4000-8000-000000000101', NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
        'E7-30 clear room', '2030-01-01 08:00:00+00', '2030-01-01 09:00:00+00', 60, 100.00, 100.00, 0.00, 100.00,
        DATE '2030-01-01', 'Africa/Cairo', now());

-- Check 1: a Venue under an active plain selling block since 2020; its only room is also blocked, DISABLED and
-- under owner review, so checks 2-4 would fail too.
INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('e7300000-0000-4000-8000-000000000012', 'e7300000-0000-4000-8000-000000000001', 'E7-30 blocked venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789');
INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('e7300000-0000-4000-8000-000000000013', 'e7300000-0000-4000-8000-000000000012', 'PLAYSTATION');
INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state, operational_restriction)
VALUES ('e7300000-0000-4000-8000-000000000111', 'e7300000-0000-4000-8000-000000000012', 'PLAYSTATION', 'E7-30 blocked venue room', 'PS5', 'DISABLED', 'OWNER_REVIEW_REQUIRED');
INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at)
VALUES ('e7300000-0000-4000-8000-000000000212', 'e7300000-0000-4000-8000-000000000012', 'E7-30 test block', true,
        '2020-01-01 00:00:00+00');
INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at)
VALUES ('e7300000-0000-4000-8000-000000000211', 'e7300000-0000-4000-8000-000000000111', 'E7-30 test block', true,
        '2020-01-01 00:00:00+00');

-- EC-AVAI-003 and check 5: one Venue with a VENUE Emergency, one with a RESOURCE Emergency on one of two rooms.
INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('e7300000-0000-4000-8000-000000000022', 'e7300000-0000-4000-8000-000000000001', 'E7-30 venue emergency venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7300000-0000-4000-8000-000000000032', 'e7300000-0000-4000-8000-000000000001', 'E7-30 resource emergency venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789');
INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('e7300000-0000-4000-8000-000000000023', 'e7300000-0000-4000-8000-000000000022', 'PLAYSTATION'),
       ('e7300000-0000-4000-8000-000000000033', 'e7300000-0000-4000-8000-000000000032', 'PLAYSTATION');
INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state, operational_restriction)
VALUES ('e7300000-0000-4000-8000-000000000121', 'e7300000-0000-4000-8000-000000000022', 'PLAYSTATION', 'E7-30 venue emergency room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300000-0000-4000-8000-000000000131', 'e7300000-0000-4000-8000-000000000032', 'PLAYSTATION', 'E7-30 failed room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300000-0000-4000-8000-000000000132', 'e7300000-0000-4000-8000-000000000032', 'PLAYSTATION', 'E7-30 working room', 'PS5', 'ACTIVE', 'NONE');

INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open, default_local_close, updated_at)
VALUES ('e7300000-0000-4000-8000-000000000025', 'e7300000-0000-4000-8000-000000000022', TIME '12:00', TIME '23:00', now()),
       ('e7300000-0000-4000-8000-000000000035', 'e7300000-0000-4000-8000-000000000032', TIME '12:00', TIME '23:00', now());

INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day, local_start, local_end,
                                     session_period, timezone, generated_from_version, generated_at)
SELECT v.session_id, v.venue_id, v.template_id, s.local_start::date, s.local_start, s.local_start + interval '22 hours',
       tstzrange(s.local_start AT TIME ZONE 'Africa/Cairo',
                 (s.local_start + interval '22 hours') AT TIME ZONE 'Africa/Cairo', '[)'),
       'Africa/Cairo', 0, now()
  FROM (VALUES ('e7300000-0000-4000-8000-000000000026'::uuid, 'e7300000-0000-4000-8000-000000000022'::uuid, 'e7300000-0000-4000-8000-000000000025'::uuid),
               ('e7300000-0000-4000-8000-000000000036'::uuid, 'e7300000-0000-4000-8000-000000000032'::uuid, 'e7300000-0000-4000-8000-000000000035'::uuid))
       AS v(session_id, venue_id, template_id)
 CROSS JOIN (SELECT date_trunc('hour', now() AT TIME ZONE 'Africa/Cairo') - interval '2 hours' AS local_start) s;

INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at, expected_end_at,
                                  declared_period, reason)
VALUES ('e7300000-0000-4000-8000-000000000027', 'VENUE', 'e7300000-0000-4000-8000-000000000022', NULL, 'ACTIVE',
        now(), now() + interval '1 hour', tstzrange(now(), now() + interval '1 hour', '[)'), 'E7-30 venue emergency');
INSERT INTO venue.venue_selling_block (id, venue_id, cause, emergency_id, active, started_at)
VALUES ('e7300000-0000-4000-8000-000000000028', 'e7300000-0000-4000-8000-000000000022', 'EMERGENCY',
        'e7300000-0000-4000-8000-000000000027', true, now());

INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at, expected_end_at,
                                  declared_period, reason)
VALUES ('e7300000-0000-4000-8000-000000000037', 'RESOURCE', 'e7300000-0000-4000-8000-000000000032',
        'e7300000-0000-4000-8000-000000000131', 'ACTIVE',
        now(), now() + interval '1 hour', tstzrange(now(), now() + interval '1 hour', '[)'), 'E7-30 resource emergency');
INSERT INTO resource.resource_selling_block (id, resource_id, cause, emergency_id, active, started_at)
VALUES ('e7300000-0000-4000-8000-000000000038', 'e7300000-0000-4000-8000-000000000131', 'EMERGENCY',
        'e7300000-0000-4000-8000-000000000037', true, now());
