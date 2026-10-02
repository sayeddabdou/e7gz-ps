-- =====================================================================================================
-- E7-23 / physical model v1.1.31 — venue photo life cycle (PCR-005; Product Owner decisions 2 and 3 Oct 2026,
-- E7-23 PR 3).
--
-- A photo row is created when an upload starts (signed PUT, arch 12 §2) and is visible only once confirmed:
--   created_at   when the upload started; the nightly clean-up hides uploads not confirmed within 24 hours
--   uploaded_at  confirm saw the stored file; the photo is visible from here (PCR-005)
--   processed_at the small (venue card) and medium (Venue Details) copies without GPS/EXIF exist; customers are
--                shown only these copies (PO decision 3 Oct 2026)
--   removed_at   hidden by the Platform Admin or the venue, or an abandoned upload; the row and the original stay
--                (PCR-005: nothing is hard-deleted)
-- sort_order is set when the photo is confirmed (last place) and by a reorder; the first visible photo is the
-- venue-card photo.
--
-- No guard, trigger, function or grant change: the baseline's table-level grants cover the new columns.
-- =====================================================================================================
ALTER TABLE venue.venue_image
    ADD COLUMN created_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN uploaded_at timestamp with time zone,
    ADD COLUMN processed_at timestamp with time zone,
    ADD COLUMN removed_at timestamp with time zone;
COMMENT ON COLUMN venue.venue_image.created_at IS E'upload started (signed PUT issued)';
COMMENT ON COLUMN venue.venue_image.uploaded_at IS E'confirmed: the stored file was checked; visible from here (PCR-005)';
COMMENT ON COLUMN venue.venue_image.processed_at IS E'small and medium copies without GPS/EXIF made; customers see only these';
COMMENT ON COLUMN venue.venue_image.removed_at IS E'hidden; row and original kept (PCR-005)';

ALTER TABLE venue.venue_image ADD CONSTRAINT ck_venue_image_life_cycle
    CHECK ((processed_at IS NULL OR uploaded_at IS NOT NULL) AND (uploaded_at IS NULL OR sort_order IS NOT NULL));

CREATE INDEX ix_venue_image_visible ON venue.venue_image USING btree (venue_id, sort_order) WITH (fillfactor='90')
    WHERE uploaded_at IS NOT NULL AND removed_at IS NULL;
