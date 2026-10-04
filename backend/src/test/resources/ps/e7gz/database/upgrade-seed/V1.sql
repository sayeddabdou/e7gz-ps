-- Upgrade-path seed, loaded right after V1 (MigrationSafetyTest): rows shaped like real data written before any later
-- migration, so every later migration is proven to apply on a database that already holds data, not only on an empty
-- one. Written against the V1 columns only. Run as the owner, outside any command - test setup only.
-- Ids: 5afe0000-0000-4000-8000-0000000<kind><n>.

INSERT INTO geo.area (id, name)
VALUES ('5afe0000-0000-4000-8000-000000000001', 'Nasr City'),
       ('5afe0000-0000-4000-8000-000000000002', 'Heliopolis');

INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('5afe0000-0000-4000-8000-000000000011', '5afe0000-0000-4000-8000-000000000001', 'Seed venue A',
        '12 Abbas El Akkad St', 'SRID=4326;POINT(31.33 30.05)'::geography, 'ACTIVE', 'Africa/Cairo', '+20223456789'),
       ('5afe0000-0000-4000-8000-000000000012', '5afe0000-0000-4000-8000-000000000002', 'Seed venue B',
        '5 Baghdad St', 'SRID=4326;POINT(31.32 30.09)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456780');

INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('5afe0000-0000-4000-8000-000000000021', '5afe0000-0000-4000-8000-000000000011', 'PLAYSTATION');

INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status, language_preference)
VALUES ('5afe0000-0000-4000-8000-000000000031', '5afe0000-0000-4000-8000-000000000011', 'seed-venue-a', 'x',
        'ENABLED', 'en');

INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash, phone_verified,
                              phone_verified_at, email_verified, referral_code, joined_at)
VALUES ('5afe0000-0000-4000-8000-000000000041', 'Seed Customer', 'seedcustomer', '+201001234567',
        'seed@example.com', 'x', true, '2026-09-01 10:00:00+00', false, 'SEEDREF1', '2026-09-01 10:00:00+00');

INSERT INTO iam.admin_account (id, email, password_hash, full_name)
VALUES ('5afe0000-0000-4000-8000-000000000051', 'seed-admin@example.com', 'x', 'Seed Admin');

INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state, operational_restriction)
VALUES ('5afe0000-0000-4000-8000-000000000061', '5afe0000-0000-4000-8000-000000000011', 'PLAYSTATION', 'Room 1', 'PS5',
        'ACTIVE', 'NONE'),
       ('5afe0000-0000-4000-8000-000000000062', '5afe0000-0000-4000-8000-000000000011', 'PLAYSTATION', 'Room 2', 'PS4',
        'ACTIVE', 'NONE');

-- Photos as V1 stored them: no life-cycle columns yet, one placed and one not yet ordered.
INSERT INTO venue.venue_image (id, venue_id, object_key, content_type, sort_order)
VALUES ('5afe0000-0000-4000-8000-000000000071', '5afe0000-0000-4000-8000-000000000011', 'venues/a/1.jpg',
        'image/jpeg', 1),
       ('5afe0000-0000-4000-8000-000000000072', '5afe0000-0000-4000-8000-000000000011', 'venues/a/2.png',
        'image/png', NULL);

-- A customer booking and a Manual Booking.
INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity, console_type, play_mode,
                             resource_name_snapshot, start_at, end_at, nominal_booked_minutes, hourly_price_snapshot,
                             venue_price_total, booking_fee_snapshot, total_amount, business_date, session_timezone,
                             created_at)
VALUES ('5afe0000-0000-4000-8000-000000000081', '5afe0000-0000-4000-8000-000000000011',
        '5afe0000-0000-4000-8000-000000000061', NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
        'Room 1', '2031-02-01 18:00:00+00', '2031-02-01 20:00:00+00', 120, 100.00, 200.00, 0.00, 200.00,
        DATE '2031-02-01', 'Africa/Cairo', now());

INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
VALUES ('5afe0000-0000-4000-8000-000000000091', '5afe0000-0000-4000-8000-000000000062',
        tstzrange('2031-02-02 08:00:00+00', '2031-02-02 10:00:00+00', '[)'), 'Seed maintenance', false);

INSERT INTO technical.scheduled_job (id, type, subject_id, subject_type, due_at, status, idempotency_key)
VALUES ('5afe0000-0000-4000-8000-000000000101', 'SEED_JOB', '5afe0000-0000-4000-8000-000000000081', 'BOOKING',
        '2031-02-01 18:10:00+00', 'PENDING', 'SEED_JOB:5afe0000-0000-4000-8000-000000000081');

INSERT INTO admin.admin_alert (id, kind, severity, subject_type, subject_id, payload, correlation_id)
VALUES ('5afe0000-0000-4000-8000-000000000111', 'SEED_ALERT', 'RISK', 'VENUE', '5afe0000-0000-4000-8000-000000000011',
        '{"source": "seed"}', '5afe0000-0000-4000-8000-000000000112');
