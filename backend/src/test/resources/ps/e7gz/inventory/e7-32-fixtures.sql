-- E7-32 concurrency harness and invariant sweep: the owner rows the race and the sweep tests insert reservations
-- against. Run once per test JVM as the migrator (owner), outside any command - test setup only.
-- Ids: e7320000-0000-4000-8000-0000000<kind><n>. All intervals lie in 2031, clear of the E7-29/E7-30 tests.

INSERT INTO geo.area (id, name)
VALUES ('e7320000-0000-4000-8000-000000000001', 'E7-32 area');

-- ACTIVE Venue: a Manual Booking refuses an ONBOARDING Venue.
INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('e7320000-0000-4000-8000-000000000002', 'e7320000-0000-4000-8000-000000000001', 'E7-32 venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ACTIVE', 'Africa/Cairo', '+20223456789');

INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('e7320000-0000-4000-8000-000000000003', 'e7320000-0000-4000-8000-000000000002', 'PLAYSTATION');

INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status, language_preference)
VALUES ('e7320000-0000-4000-8000-000000000004', 'e7320000-0000-4000-8000-000000000002', 'e7-32-venue', 'x',
        'ENABLED', 'en');

-- 101: the N-racer race. 102: MAINTENANCE / MAINTENANCE / CLOSURE guard rows. 103: released history.
-- 104: valid non-overlapping commitments.
INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state, operational_restriction)
VALUES ('e7320000-0000-4000-8000-000000000101', 'e7320000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-32 race room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7320000-0000-4000-8000-000000000102', 'e7320000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-32 maintenance room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7320000-0000-4000-8000-000000000103', 'e7320000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-32 released-history room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7320000-0000-4000-8000-000000000104', 'e7320000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-32 commitments room', 'PS5', 'ACTIVE', 'NONE');

-- Owner of the sweep tests' BOOKING rows: a future Manual Booking, CONFIRMED. The ledger does not tie a row's Resource
-- or interval to its Booking, so one Booking can own them all.
INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity, console_type, play_mode,
                             resource_name_snapshot, start_at, end_at, nominal_booked_minutes, hourly_price_snapshot,
                             venue_price_total, booking_fee_snapshot, total_amount, business_date, session_timezone,
                             created_at)
VALUES ('e7320000-0000-4000-8000-000000000201', 'e7320000-0000-4000-8000-000000000002',
        'e7320000-0000-4000-8000-000000000104', NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
        'E7-32 commitments room', '2031-01-05 08:00:00+00', '2031-01-05 09:00:00+00', 60, 100.00, 100.00, 0.00, 100.00,
        DATE '2031-01-05', 'Africa/Cairo', now());

-- Two Maintenance windows on the maintenance room that overlap each other (A1: MAINTENANCE <-> MAINTENANCE is allowed),
-- and a partial Venue Closure overlapping both (a Closure may overlap existing Maintenance).
INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
VALUES ('e7320000-0000-4000-8000-000000000301', 'e7320000-0000-4000-8000-000000000102',
        tstzrange('2031-01-01 08:00:00+00', '2031-01-01 10:00:00+00', '[)'), 'E7-32 maintenance 1', false),
       ('e7320000-0000-4000-8000-000000000302', 'e7320000-0000-4000-8000-000000000102',
        tstzrange('2031-01-01 09:00:00+00', '2031-01-01 11:00:00+00', '[)'), 'E7-32 maintenance 2', false);

INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
VALUES ('e7320000-0000-4000-8000-000000000401', 'e7320000-0000-4000-8000-000000000002',
        tstzrange('2031-01-01 09:30:00+00', '2031-01-01 10:30:00+00', '[)'), 'PARTIAL', 'E7-32 closure');
