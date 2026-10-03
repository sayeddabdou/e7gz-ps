-- E7-29 Reservation ledger: the minimum owner rows the ledger tests insert reservations against.
-- Run once per test JVM as the migrator (owner), outside any command - test setup only, never through the
-- Platform venue APIs. Ids are fixed and readable: e7290000-0000-4000-8000-0000000<kind><n>.
-- The Booking, Maintenance and Closure test intervals lie in January 2030. The Tournament owner-shape case and its
-- Operating Session are on 1 February 2030, so the Manual Booking below (January) is out-of-hours.

-- Area, ACTIVE Venue (a Manual Booking and a Tournament both refuse an ONBOARDING Venue), its activity and account.
INSERT INTO geo.area (id, name)
VALUES ('e7290000-0000-4000-8000-000000000001', 'E7-29 ledger area');

INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('e7290000-0000-4000-8000-000000000002', 'e7290000-0000-4000-8000-000000000001', 'E7-29 ledger venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ACTIVE', 'Africa/Cairo', '+20223456789');

INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('e7290000-0000-4000-8000-000000000003', 'e7290000-0000-4000-8000-000000000002', 'PLAYSTATION');

INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status, language_preference)
VALUES ('e7290000-0000-4000-8000-000000000004', 'e7290000-0000-4000-8000-000000000002', 'e7-29-ledger-venue', 'x',
        'ENABLED', 'en');

-- One Resource per test, so committed rows of one test never meet another test's intervals.
INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state, operational_restriction)
VALUES ('e7290000-0000-4000-8000-000000000101', 'e7290000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-29 race room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7290000-0000-4000-8000-000000000102', 'e7290000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-29 no-show room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7290000-0000-4000-8000-000000000103', 'e7290000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-29 one-way room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7290000-0000-4000-8000-000000000104', 'e7290000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-29 owner-shape room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7290000-0000-4000-8000-000000000105', 'e7290000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-29 translation room', 'PS5', 'ACTIVE', 'NONE'),
       ('e7290000-0000-4000-8000-000000000106', 'e7290000-0000-4000-8000-000000000002', 'PLAYSTATION', 'E7-29 tournament room', 'PS5', 'ACTIVE', 'NONE');

-- Owner of every BOOKING / LATE_CONFIRM_HOLD row in the tests: a future Manual Booking, CONFIRMED, out of hours.
-- The ledger does not tie a row's Resource or interval to its Booking, so one Booking can own them all.
INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity, console_type, play_mode,
                             resource_name_snapshot, start_at, end_at, nominal_booked_minutes, hourly_price_snapshot,
                             venue_price_total, booking_fee_snapshot, total_amount, business_date, session_timezone,
                             created_at)
VALUES ('e7290000-0000-4000-8000-000000000201', 'e7290000-0000-4000-8000-000000000002',
        'e7290000-0000-4000-8000-000000000101', NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
        'E7-29 race room', '2030-01-01 08:00:00+00', '2030-01-01 09:00:00+00', 60, 100.00, 100.00, 0.00, 100.00,
        DATE '2030-01-01', 'Africa/Cairo', now());

-- Owner of the MAINTENANCE row (the row must match it exactly: same Resource and period).
INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
VALUES ('e7290000-0000-4000-8000-000000000301', 'e7290000-0000-4000-8000-000000000104',
        tstzrange('2030-01-03 08:00:00+00', '2030-01-03 09:00:00+00', '[)'), 'E7-29 ledger maintenance', false);

-- Owner of the CLOSURE row (the row must match its period, on a Resource of the Venue).
INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
VALUES ('e7290000-0000-4000-8000-000000000401', 'e7290000-0000-4000-8000-000000000002',
        tstzrange('2030-01-04 08:00:00+00', '2030-01-04 09:00:00+00', '[)'), 'PARTIAL', 'E7-29 ledger closure');

-- Operating Session a Tournament owner must reference. The Tournament itself is created inside the test command,
-- because creating one is a command-scoped write.
INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open, default_local_close, updated_at)
VALUES ('e7290000-0000-4000-8000-000000000005', 'e7290000-0000-4000-8000-000000000002', TIME '12:00', TIME '23:00', now());

INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day, local_start, local_end,
                                     session_period, timezone, generated_from_version, generated_at)
VALUES ('e7290000-0000-4000-8000-000000000006', 'e7290000-0000-4000-8000-000000000002',
        'e7290000-0000-4000-8000-000000000005', DATE '2030-02-01',
        TIMESTAMP '2030-02-01 12:00', TIMESTAMP '2030-02-01 23:00',
        tstzrange(TIMESTAMP '2030-02-01 12:00' AT TIME ZONE 'Africa/Cairo',
                  TIMESTAMP '2030-02-01 23:00' AT TIME ZONE 'Africa/Cairo', '[)'),
        'Africa/Cairo', 0, now());
