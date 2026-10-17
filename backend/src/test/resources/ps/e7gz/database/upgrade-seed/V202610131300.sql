-- Upgrade-path seed, loaded right after V202610131300 and before V202610171400 (operating session supersession): an
-- opening template of seed venue A, two of its sessions, and a Manual Booking that snapshots the first one - sessions
-- and a referenced session as they exist before superseded_at, so the migration and every later one run over them.

INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open, default_local_close, default_full_day,
                                          version, updated_at)
VALUES ('5afe0000-0000-4000-8000-000000000201', '5afe0000-0000-4000-8000-000000000011', TIME '12:00', TIME '23:00',
        false, 1, '2026-10-01 10:00:00+00');

INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day, local_start, local_end,
                                     session_period, timezone, generated_from_version, generated_at)
VALUES ('5afe0000-0000-4000-8000-000000000202', '5afe0000-0000-4000-8000-000000000011',
        '5afe0000-0000-4000-8000-000000000201', DATE '2031-03-01', TIMESTAMP '2031-03-01 12:00',
        TIMESTAMP '2031-03-01 23:00', tstzrange('2031-03-01 10:00:00+00', '2031-03-01 21:00:00+00', '[)'),
        'Africa/Cairo', 1, '2026-10-01 10:00:00+00'),
       ('5afe0000-0000-4000-8000-000000000203', '5afe0000-0000-4000-8000-000000000011',
        '5afe0000-0000-4000-8000-000000000201', DATE '2031-03-02', TIMESTAMP '2031-03-02 12:00',
        TIMESTAMP '2031-03-02 23:00', tstzrange('2031-03-02 10:00:00+00', '2031-03-02 21:00:00+00', '[)'),
        'Africa/Cairo', 1, '2026-10-01 10:00:00+00');

INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity, console_type, play_mode,
                             resource_name_snapshot, start_at, end_at, nominal_booked_minutes, hourly_price_snapshot,
                             venue_price_total, booking_fee_snapshot, total_amount, business_date, operating_day,
                             operating_session_id, session_local_start, session_local_end, session_timezone,
                             created_at)
VALUES ('5afe0000-0000-4000-8000-000000000204', '5afe0000-0000-4000-8000-000000000011',
        '5afe0000-0000-4000-8000-000000000061', NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
        'Room 1', '2031-03-01 14:00:00+00', '2031-03-01 16:00:00+00', 120, 100.00, 200.00, 0.00, 200.00,
        DATE '2031-03-01', DATE '2031-03-01', '5afe0000-0000-4000-8000-000000000202', TIMESTAMP '2031-03-01 12:00',
        TIMESTAMP '2031-03-01 23:00', 'Africa/Cairo', now());
