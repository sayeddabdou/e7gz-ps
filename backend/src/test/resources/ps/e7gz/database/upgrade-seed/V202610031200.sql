-- Upgrade-path seed, loaded right after V202610031200 (photo life cycle): photos using the new columns, so every
-- later migration also runs over confirmed, processed and removed photos.

INSERT INTO venue.venue_image (id, venue_id, object_key, content_type, sort_order, uploaded_at, processed_at, removed_at)
VALUES ('5afe0000-0000-4000-8000-000000000073', '5afe0000-0000-4000-8000-000000000011', 'venues/a/3.jpg',
        'image/jpeg', 2, '2026-10-03 10:00:00+00', '2026-10-03 10:01:00+00', NULL),
       ('5afe0000-0000-4000-8000-000000000074', '5afe0000-0000-4000-8000-000000000011', 'venues/a/4.jpg',
        'image/jpeg', 3, '2026-10-03 10:00:00+00', NULL, '2026-10-03 11:00:00+00'),
       ('5afe0000-0000-4000-8000-000000000075', '5afe0000-0000-4000-8000-000000000011', 'venues/a/5.jpg',
        'image/jpeg', NULL, NULL, NULL, NULL);
