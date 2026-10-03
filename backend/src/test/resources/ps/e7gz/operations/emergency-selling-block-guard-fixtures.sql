-- E7-30 / v1.1.32 operations.guard_emergency_selling_block(): Venues with ACTIVE Emergencies and their selling blocks.
-- Run once per test JVM as the migrator (owner), outside any command, in ONE transaction: the deferred
-- tr_emergency_block_consistency_* triggers need each Emergency and its selling block to commit together.
-- Every trigger stays on. Ids: e7300400-0000-4000-8000-0000000<kind><n>.
--
-- The database declares an Emergency at statement_timestamp() (guard_emergency_lifecycle) inside a materialized
-- Operating Session, so each Emergency here is declared "now" with Expected End now() + 1 hour, in a session that
-- starts two hours before now.

INSERT INTO geo.area (id, name)
VALUES ('e7300400-0000-4000-8000-000000000001', 'E7-30 emergency selling block guard area');

-- 002 venue close | 003 resource close | 004 venue subject | 005 another venue | 006 resource subject
-- 008 venue delete | 009 resource delete | 00a plain (non-emergency) blocks
INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
SELECT v.id, 'e7300400-0000-4000-8000-000000000001', v.name, 'address', 'SRID=4326;POINT(31.2 30.0)'::geography,
       'ONBOARDING', 'Africa/Cairo', '+20223456789'
  FROM (VALUES ('e7300400-0000-4000-8000-000000000002'::uuid, 'E7-30 guard venue close'),
               ('e7300400-0000-4000-8000-000000000003'::uuid, 'E7-30 guard resource close'),
               ('e7300400-0000-4000-8000-000000000004'::uuid, 'E7-30 guard venue subject'),
               ('e7300400-0000-4000-8000-000000000005'::uuid, 'E7-30 guard another venue'),
               ('e7300400-0000-4000-8000-000000000006'::uuid, 'E7-30 guard resource subject'),
               ('e7300400-0000-4000-8000-000000000008'::uuid, 'E7-30 guard venue delete'),
               ('e7300400-0000-4000-8000-000000000009'::uuid, 'E7-30 guard resource delete'),
               ('e7300400-0000-4000-8000-00000000000a'::uuid, 'E7-30 guard plain blocks')) AS v(id, name);

INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('e7300400-0000-4000-8000-000000000903', 'e7300400-0000-4000-8000-000000000003', 'PLAYSTATION'),
       ('e7300400-0000-4000-8000-000000000906', 'e7300400-0000-4000-8000-000000000006', 'PLAYSTATION'),
       ('e7300400-0000-4000-8000-000000000909', 'e7300400-0000-4000-8000-000000000009', 'PLAYSTATION'),
       ('e7300400-0000-4000-8000-00000000090a', 'e7300400-0000-4000-8000-00000000000a', 'PLAYSTATION');

INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state, operational_restriction)
VALUES ('e7300400-0000-4000-8000-000000000103', 'e7300400-0000-4000-8000-000000000003', 'PLAYSTATION', 'E7-30 guard close room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300400-0000-4000-8000-000000000106', 'e7300400-0000-4000-8000-000000000006', 'PLAYSTATION', 'E7-30 guard failed room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300400-0000-4000-8000-000000000107', 'e7300400-0000-4000-8000-000000000006', 'PLAYSTATION', 'E7-30 guard other room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300400-0000-4000-8000-000000000109', 'e7300400-0000-4000-8000-000000000009', 'PLAYSTATION', 'E7-30 guard delete room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7300400-0000-4000-8000-00000000010a', 'e7300400-0000-4000-8000-00000000000a', 'PLAYSTATION', 'E7-30 guard plain room', 'PS5', 'ACTIVE', 'NONE');

-- An Operating Session around now for each Emergency venue (template 5nn, session 6nn).
INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open, default_local_close, updated_at)
SELECT ('e7300400-0000-4000-8000-000000000' || n)::uuid, ('e7300400-0000-4000-8000-000000000' || v)::uuid,
       TIME '12:00', TIME '23:00', now()
  FROM (VALUES ('502', '002'), ('503', '003'), ('504', '004'), ('506', '006'), ('508', '008'), ('509', '009'))
       AS t(n, v);

INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day, local_start, local_end,
                                     session_period, timezone, generated_from_version, generated_at)
SELECT ('e7300400-0000-4000-8000-000000000' || t.session)::uuid, ('e7300400-0000-4000-8000-000000000' || t.venue)::uuid,
       ('e7300400-0000-4000-8000-000000000' || t.template)::uuid,
       s.local_start::date, s.local_start, s.local_start + interval '22 hours',
       tstzrange(s.local_start AT TIME ZONE 'Africa/Cairo',
                 (s.local_start + interval '22 hours') AT TIME ZONE 'Africa/Cairo', '[)'),
       'Africa/Cairo', 0, now()
  FROM (VALUES ('602', '002', '502'), ('603', '003', '503'), ('604', '004', '504'), ('606', '006', '506'),
               ('608', '008', '508'), ('609', '009', '509')) AS t(session, venue, template)
 CROSS JOIN (SELECT date_trunc('hour', now() AT TIME ZONE 'Africa/Cairo') - interval '2 hours' AS local_start) s;

-- ACTIVE Emergencies (7nn) and their selling blocks (8nn); declared_at and started_at are set by the triggers.
INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at, expected_end_at,
                                  declared_period, reason)
SELECT ('e7300400-0000-4000-8000-000000000' || t.emergency)::uuid, t.scope,
       ('e7300400-0000-4000-8000-000000000' || t.venue)::uuid,
       CASE WHEN t.resource IS NULL THEN NULL ELSE ('e7300400-0000-4000-8000-000000000' || t.resource)::uuid END,
       'ACTIVE', now(), now() + interval '1 hour', tstzrange(now(), now() + interval '1 hour', '[)'),
       'E7-30 guard emergency'
  FROM (VALUES ('702', 'VENUE', '002', NULL), ('703', 'RESOURCE', '003', '103'), ('704', 'VENUE', '004', NULL),
               ('706', 'RESOURCE', '006', '106'), ('708', 'VENUE', '008', NULL), ('709', 'RESOURCE', '009', '109'))
       AS t(emergency, scope, venue, resource);

INSERT INTO venue.venue_selling_block (id, venue_id, cause, emergency_id, active, started_at)
SELECT ('e7300400-0000-4000-8000-000000000' || t.block)::uuid, ('e7300400-0000-4000-8000-000000000' || t.venue)::uuid,
       'EMERGENCY', ('e7300400-0000-4000-8000-000000000' || t.emergency)::uuid, true, now()
  FROM (VALUES ('802', '002', '702'), ('804', '004', '704'), ('808', '008', '708')) AS t(block, venue, emergency);

INSERT INTO resource.resource_selling_block (id, resource_id, cause, emergency_id, active, started_at)
SELECT ('e7300400-0000-4000-8000-000000000' || t.block)::uuid,
       ('e7300400-0000-4000-8000-000000000' || t.resource)::uuid,
       'EMERGENCY', ('e7300400-0000-4000-8000-000000000' || t.emergency)::uuid, true, now()
  FROM (VALUES ('803', '103', '703'), ('806', '106', '706'), ('809', '109', '709')) AS t(block, resource, emergency);
