-- E7-31 OperatingSessions read: Venues for the Operating Session containment tests.
-- Run once per test JVM as the migrator (owner), outside any command - test setup only. Ids:
-- e7310100-0000-4000-8000-0000000<kind><n>.
--   ...002, ...003: no opening hours; each test writes its template and sessions inside a command and rolls back,
--                   so the nightly horizon extension (which only visits venues with a template) never touches them.
--   ...004:         committed hours whose template (10:00-12:00) disagrees with its one past, materialized session
--                   (Wednesday 10 June 2025, 18:00 -> Thursday 02:00 Cairo). A started session is never regenerated.

INSERT INTO geo.area (id, name)
VALUES ('e7310100-0000-4000-8000-000000000001', 'E7-31 operating session area');

INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('e7310100-0000-4000-8000-000000000002', 'e7310100-0000-4000-8000-000000000001', 'E7-31 session venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7310100-0000-4000-8000-000000000003', 'e7310100-0000-4000-8000-000000000001', 'E7-31 neighbour venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7310100-0000-4000-8000-000000000004', 'e7310100-0000-4000-8000-000000000001', 'E7-31 committed venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789');

INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open, default_local_close, updated_at)
VALUES ('e7310100-0000-4000-8000-000000000104', 'e7310100-0000-4000-8000-000000000004', TIME '10:00', TIME '12:00',
        now());

INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day, local_start, local_end,
                                     session_period, timezone, generated_from_version, generated_at)
VALUES ('e7310100-0000-4000-8000-000000000204', 'e7310100-0000-4000-8000-000000000004',
        'e7310100-0000-4000-8000-000000000104', DATE '2025-06-10',
        TIMESTAMP '2025-06-10 18:00', TIMESTAMP '2025-06-11 02:00',
        tstzrange(TIMESTAMP '2025-06-10 18:00' AT TIME ZONE 'Africa/Cairo',
                  TIMESTAMP '2025-06-11 02:00' AT TIME ZONE 'Africa/Cairo', '[)'),
        'Africa/Cairo', 0, now());
