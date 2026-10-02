-- =====================================================================================================
-- E7-23 / physical model v1.1.30 — venue creation time and unique area names (Product Owner decisions,
-- 2 Oct 2026, E7-23 PR 2 review).
--
-- 1. venue.venue.created_at: when the Platform Admin created the venue. The admin venue list is ordered by it
--    (newest first, then id), so renaming a venue can no longer move it between pages of the cursor list.
--    Existing rows receive the migration instant.
-- 2. ux_area_name_normalized: two areas may not share a name, compared trimmed and case-insensitive (the same
--    rule as ux_admin_email_normalized). The API maps it to AREA_NAME_TAKEN.
--
-- No guard, trigger, function or grant changes: the table-level grants of the security baseline cover the new
-- column, and no trigger on venue.venue or geo.area compares whole rows.
-- =====================================================================================================
ALTER TABLE venue.venue ADD COLUMN created_at timestamp with time zone NOT NULL DEFAULT clock_timestamp();
COMMENT ON COLUMN venue.venue.created_at IS E'when the Platform Admin created the venue; admin list order';

CREATE INDEX ix_venue_created_at ON venue.venue USING btree (created_at, id) WITH (fillfactor='90');

CREATE UNIQUE INDEX ux_area_name_normalized ON geo.area USING btree (lower(btrim(name))) WITH (fillfactor='90');
