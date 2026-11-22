-- Upgrade-path seed, loaded right after V202611201200 (E7-81, v1.1.44): a Maintenance window and a Closure deleted by a
-- command (deleted_at = the command instant), so every later migration also runs over soft-deleted rows. The V1 seed
-- Maintenance window (…091) stays live. Run as the owner, in one transaction - test setup only.

DO $seed_v1_1_44$
DECLARE at timestamptz;
BEGIN
 at := tournament.begin_command(ARRAY[]::uuid[], ARRAY[]::uuid[], ARRAY[]::uuid[], ARRAY[]::uuid[]);
 INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
 VALUES ('5afe0000-0000-4000-8000-000000000291', '5afe0000-0000-4000-8000-000000000062',
         tstzrange('2031-03-02 08:00:00+00', '2031-03-02 10:00:00+00', '[)'), 'Seed deleted maintenance', false);
 UPDATE operations.maintenance_window SET deleted_at = at WHERE id = '5afe0000-0000-4000-8000-000000000291';
 INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
 VALUES ('5afe0000-0000-4000-8000-000000000292', '5afe0000-0000-4000-8000-000000000012',
         tstzrange('2031-03-03 00:00:00+00', '2031-03-04 00:00:00+00', '[)'), 'FULL_DAY', 'Seed deleted closure');
 UPDATE operations.venue_closure SET deleted_at = at WHERE id = '5afe0000-0000-4000-8000-000000000292';
END;
$seed_v1_1_44$;
