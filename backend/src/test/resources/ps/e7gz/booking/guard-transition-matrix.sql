-- E7-48 / v1.1.34 (PCR-007): the whole decision table of booking.guard_booking_transition, against the V1 copy
-- pg_temp.v1_guard_booking_transition() the test creates first. Scratch temp rows carry only what the guard reads
-- (source, status, terminal_reason, start_at, end_at), as test_behaviour_v127.sh does; nothing is written to a real
-- table and the test rolls the transaction back. Results land in pg_temp.guard_matrix.

CREATE TEMP TABLE bk_v1 (id integer PRIMARY KEY, source text, status text, terminal_reason text,
                         start_at timestamptz, end_at timestamptz);
CREATE TEMP TABLE bk_migrated (LIKE bk_v1 INCLUDING ALL);
CREATE TRIGGER guard BEFORE UPDATE OF status, terminal_reason ON pg_temp.bk_v1
    FOR EACH ROW EXECUTE FUNCTION pg_temp.v1_guard_booking_transition();
CREATE TRIGGER guard BEFORE UPDATE OF status, terminal_reason ON pg_temp.bk_migrated
    FOR EACH ROW EXECUTE FUNCTION booking.guard_booking_transition();
CREATE TEMP TABLE guard_matrix (phase text, from_s text, to_s text, reason text, source text,
                                v1 boolean, migrated boolean);

-- One attempt on a fresh row: true when the guard lets it through, false when it raises (P0001 only).
CREATE FUNCTION pg_temp.try_transition(p_table text, p_source text, p_from text, p_to text, p_reason text,
                                       p_start timestamptz)
RETURNS boolean LANGUAGE plpgsql AS $try$
BEGIN
    EXECUTE format('DELETE FROM pg_temp.%I', p_table);
    EXECUTE format('INSERT INTO pg_temp.%I VALUES (1, $1, $2, NULL, $3, $3 + interval ''1 hour'')', p_table)
        USING p_source, p_from, p_start;
    BEGIN
        EXECUTE format('UPDATE pg_temp.%I SET status = $1, terminal_reason = $2 WHERE id = 1', p_table)
            USING p_to, p_reason;
        RETURN true;
    EXCEPTION WHEN raise_exception THEN
        RETURN false;
    END;
END;
$try$;

DO $matrix$
DECLARE
    statuses text[] := ARRAY['PENDING','VENUE_RESPONSE_EXPIRED','AWAITING_CUSTOMER_CONFIRMATION','CONFIRMED','ACTIVE',
                             'COMPLETED','REJECTED','CANCELLED','NO_SHOW'];
    reasons text[] := ARRAY['CUSTOMER_CANCELLED','CUSTOMER_DECLINED_LATE_CONFIRM','CUSTOMER_RECONFIRM_TIMEOUT',
                            'VENUE_REJECTED','REJECTED_AFTER_EXPIRY','VENUE_CANCELLED_CONFIRMED_BOOKING',
                            'VENUE_CANCELLED_MANUAL_BOOKING','VENUE_DID_NOT_RESPOND_IN_TIME',
                            'VENUE_OPENING_HOURS_CHANGED','VENUE_CLOSURE','VENUE_EMERGENCY_CLOSURE',
                            'RESOURCE_EMERGENCY_OUTAGE','VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY',
                            'RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY','LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY',
                            'RESOURCE_OWNER_REVIEW_REQUIRED','VENUE_ACCOUNT_DISABLED',
                            'SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR','USER_BANNED','ADMIN_CANCELLED',
                            'ADMIN_CORRECTION','ADMIN_NO_SHOW_REVERSAL'];
    phase text; v_start timestamptz; src text; f text; t text; r text;
BEGIN
    -- Exactly the reasons the database accepts (ck_booking_terminal_reason_values), so a reason added later fails
    -- this test instead of escaping it.
    IF (SELECT array_agg(m[1] ORDER BY m[1])
          FROM pg_constraint c, regexp_matches(pg_get_constraintdef(c.oid), '''([A-Z_]+)''', 'g') AS m
         WHERE c.conname = 'ck_booking_terminal_reason_values' AND c.conrelid = 'booking.booking'::regclass)
       IS DISTINCT FROM (SELECT array_agg(x ORDER BY x) FROM unnest(reasons) x) THEN
        RAISE EXCEPTION 'reason list differs from ck_booking_terminal_reason_values';
    END IF;
    FOREACH phase IN ARRAY ARRAY['future','past'] LOOP
        v_start := CASE phase WHEN 'future' THEN now() + interval '1 day' ELSE now() - interval '1 day' END;
        FOREACH src IN ARRAY ARRAY['ONLINE','MANUAL'] LOOP
            FOREACH f IN ARRAY statuses LOOP
                FOREACH t IN ARRAY statuses LOOP
                    FOREACH r IN ARRAY array_append(reasons, NULL::text) LOOP
                        INSERT INTO pg_temp.guard_matrix
                        VALUES (phase, f, t, r, src,
                                pg_temp.try_transition('bk_v1', src, f, t, r, v_start),
                                pg_temp.try_transition('bk_migrated', src, f, t, r, v_start));
                    END LOOP;
                END LOOP;
            END LOOP;
        END LOOP;
    END LOOP;
END;
$matrix$;
