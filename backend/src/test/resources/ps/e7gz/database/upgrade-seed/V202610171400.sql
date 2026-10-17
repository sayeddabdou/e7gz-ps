-- Upgrade-path seed, loaded right after V202610171400 (operating session supersession, v1.1.36): the referenced
-- session of 1 Mar 2031 superseded as an hours change does it, the day's current session (now 16:00-23:00) beside it
-- with a Manual Booking that snapshots it, and an out-of-hours walk-in inside the OLD window only - no snapshot - so
-- every later migration also runs over superseded history and two sessions of one operating day.

UPDATE venue.venue_opening_template
   SET default_local_open = TIME '16:00', version = 2, updated_at = '2026-10-17 10:00:00+00'
 WHERE id = '5afe0000-0000-4000-8000-000000000201';

UPDATE venue.operating_session SET superseded_at = '2026-10-17 10:00:00+00'
 WHERE id = '5afe0000-0000-4000-8000-000000000202';

INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day, local_start, local_end,
                                     session_period, timezone, generated_from_version, generated_at)
VALUES ('5afe0000-0000-4000-8000-000000000205', '5afe0000-0000-4000-8000-000000000011',
        '5afe0000-0000-4000-8000-000000000201', DATE '2031-03-01', TIMESTAMP '2031-03-01 16:00',
        TIMESTAMP '2031-03-01 23:00', tstzrange('2031-03-01 14:00:00+00', '2031-03-01 21:00:00+00', '[)'),
        'Africa/Cairo', 2, '2026-10-17 10:00:00+00');

INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity, console_type, play_mode,
                             resource_name_snapshot, start_at, end_at, nominal_booked_minutes, hourly_price_snapshot,
                             venue_price_total, booking_fee_snapshot, total_amount, business_date, operating_day,
                             operating_session_id, session_local_start, session_local_end, session_timezone,
                             created_at)
VALUES ('5afe0000-0000-4000-8000-000000000206', '5afe0000-0000-4000-8000-000000000011',
        '5afe0000-0000-4000-8000-000000000062', NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS4', 'SINGLE',
        'Room 2', '2031-03-01 18:00:00+00', '2031-03-01 19:00:00+00', 60, 100.00, 100.00, 0.00, 100.00,
        DATE '2031-03-01', DATE '2031-03-01', '5afe0000-0000-4000-8000-000000000205', TIMESTAMP '2031-03-01 16:00',
        TIMESTAMP '2031-03-01 23:00', 'Africa/Cairo', now());

INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity, console_type, play_mode,
                             resource_name_snapshot, start_at, end_at, nominal_booked_minutes, hourly_price_snapshot,
                             venue_price_total, booking_fee_snapshot, total_amount, business_date, session_timezone,
                             created_at)
VALUES ('5afe0000-0000-4000-8000-000000000207', '5afe0000-0000-4000-8000-000000000011',
        '5afe0000-0000-4000-8000-000000000062', NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS4', 'SINGLE',
        'Room 2', '2031-03-01 11:00:00+00', '2031-03-01 12:00:00+00', 60, 100.00, 100.00, 0.00, 100.00,
        DATE '2031-03-01', 'Africa/Cairo', now());
