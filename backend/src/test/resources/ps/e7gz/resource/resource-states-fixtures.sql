-- E7-30 ResourceStates read: one Resource per state combination, and Resources with an active / ended selling block.
-- Run once per test JVM as the migrator (owner), outside any command - test setup only. The blocks are plain
-- (emergency_id NULL) so their started_at / ended_at are fixed. Ids: e7300200-0000-4000-8000-0000000<kind><n>.

INSERT INTO geo.area (id, name)
VALUES ('e7300200-0000-4000-8000-000000000001', 'E7-30 resource states area');

INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('e7300200-0000-4000-8000-000000000002', 'e7300200-0000-4000-8000-000000000001', 'E7-30 resource states venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789');

INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('e7300200-0000-4000-8000-000000000003', 'e7300200-0000-4000-8000-000000000002', 'PLAYSTATION'),
       ('e7300200-0000-4000-8000-000000000004', 'e7300200-0000-4000-8000-000000000002', 'BILLIARDS');

INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state, operational_restriction)
VALUES ('e7300200-0000-4000-8000-000000000101', 'e7300200-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 active room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300200-0000-4000-8000-000000000102', 'e7300200-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 disabled room', 'PS5', 'DISABLED', 'NONE'),
       ('e7300200-0000-4000-8000-000000000103', 'e7300200-0000-4000-8000-000000000002', 'BILLIARDS', 'E7-30 archived table', NULL, 'ARCHIVED', 'NONE'),
       ('e7300200-0000-4000-8000-000000000104', 'e7300200-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 review room', 'PS4', 'ACTIVE', 'OWNER_REVIEW_REQUIRED'),
       ('e7300200-0000-4000-8000-000000000105', 'e7300200-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 blocked room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300200-0000-4000-8000-000000000106', 'e7300200-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 block-ended room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300200-0000-4000-8000-000000000107', 'e7300200-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-30 in-command room', 'PS5', 'ACTIVE', 'NONE');

-- Active since 2030-01-01 10:00 UTC, never ended.
INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at, ended_at)
VALUES ('e7300200-0000-4000-8000-000000000201', 'e7300200-0000-4000-8000-000000000105', 'E7-30 test block', true,
        '2030-01-01 10:00:00+00', NULL);

-- In force [2030-01-01 10:00, 2030-01-02 10:00) UTC, then ended.
INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at, ended_at)
VALUES ('e7300200-0000-4000-8000-000000000202', 'e7300200-0000-4000-8000-000000000106', 'E7-30 test block', false,
        '2030-01-01 10:00:00+00', '2030-01-02 10:00:00+00');
