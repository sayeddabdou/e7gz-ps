-- E7-30 VenueSellingBlocks read: Venues with an active, an ended and no selling block.
-- Run once per test JVM as the migrator (owner), outside any command - test setup only. The blocks are plain
-- (emergency_id NULL) so their started_at / ended_at are fixed. Ids: e7300100-0000-4000-8000-0000000<kind><n>.

INSERT INTO geo.area (id, name)
VALUES ('e7300100-0000-4000-8000-000000000001', 'E7-30 venue selling block area');

INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('e7300100-0000-4000-8000-000000000002', 'e7300100-0000-4000-8000-000000000001', 'E7-30 blocked venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7300100-0000-4000-8000-000000000003', 'e7300100-0000-4000-8000-000000000001', 'E7-30 block-ended venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7300100-0000-4000-8000-000000000004', 'e7300100-0000-4000-8000-000000000001', 'E7-30 unblocked venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('e7300100-0000-4000-8000-000000000005', 'e7300100-0000-4000-8000-000000000001', 'E7-30 in-command venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789');

-- Active since 2030-01-01 10:00 UTC, never ended.
INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at, ended_at)
VALUES ('e7300100-0000-4000-8000-000000000101', 'e7300100-0000-4000-8000-000000000002', 'E7-30 test block', true,
        '2030-01-01 10:00:00+00', NULL);

-- In force [2030-01-01 10:00, 2030-01-02 10:00) UTC, then ended.
INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at, ended_at)
VALUES ('e7300100-0000-4000-8000-000000000102', 'e7300100-0000-4000-8000-000000000003', 'E7-30 test block', false,
        '2030-01-01 10:00:00+00', '2030-01-02 10:00:00+00');
