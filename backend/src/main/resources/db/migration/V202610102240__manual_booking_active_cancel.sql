-- =====================================================================================================
-- E7-48 / physical model v1.1.34 — PCR-007: a venue cancels an ACTIVE Manual Booking
-- (Product Owner decision 10 Oct 2026, E7-48; PCR-007 — Cancel an ACTIVE Manual Booking).
--
-- PCR-007: venue staff may cancel a Manual Booking that is CONFIRMED or ACTIVE, without the Owner Password,
-- reason VENUE_CANCELLED_MANUAL_BOOKING. booking.guard_booking_transition (V1) refused ACTIVE -> CANCELLED with
-- that reason. Two changes, nothing else:
--
-- 1. booking.guard_booking_transition(): the final V1 definition copied verbatim, with ONE branch widened:
--    ACTIVE -> CANCELLED also accepts VENUE_CANCELLED_MANUAL_BOOKING, and only when source = 'MANUAL'. Every other
--    transition, reason list, time rule and error message is unchanged. tr_03_booking_transition_guard is not
--    recreated and keeps pointing at the function.
--
-- 2. booking.assert_manual_active_cancel_provenance() + constraint trigger
--    tr_booking_manual_active_cancel_provenance (AFTER UPDATE OF status, DEFERRABLE INITIALLY DEFERRED): at commit,
--    a MANUAL booking that went ACTIVE -> CANCELLED / VENUE_CANCELLED_MANUAL_BOOKING must have exactly one
--    ACTIVE -> CANCELLED status-transition row in booking.booking_history, and that row must carry the same reason,
--    occurred_at = the booking's terminal_at, actor_type VENUE and an actor_id that is a venue account of the
--    booking's own venue. A missing, duplicate or mismatched row, or any other actor (ADMIN, SYSTEM, NULL, another
--    venue's account), rolls the whole command back. The guard cannot check this itself: it runs BEFORE the row is
--    updated, and the history row is written after it in the same transaction (the same commit-time pattern as the
--    attendance and Ban-cascade history checks).
--
-- SECURITY LIMITATION: the check verifies the RECORDED actor provenance, not an independently authenticated
-- identity. The venue API and the worker share e7gz_app_runtime, and the database never sees the venue's access
-- token, so application code that wrote a false VENUE history row would pass. The authority for "this is the
-- signed-in venue" stays the API layer; the database guarantees the cancellation and its history agree, with a
-- real account of that venue. No session marker is introduced. Same assurance level as the attendance VENUE checks.
--
-- Unchanged on purpose: every table, column, index, constraint, grant and data row; begin_command and every other
-- trigger. PUBLIC is revoked on the new function here; the security baseline grants EXECUTE on it after every
-- migration, like every booking function (a trigger function needs none to fire). Internal invariant raises
-- carry no guard identifier (HTTP 500 if ever reached).
-- Engine-lane functions (Booking): need the Engine owner's review.
-- =====================================================================================================
CREATE OR REPLACE FUNCTION booking.guard_booking_transition()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE r text := NEW.terminal_reason; ok boolean := false;
BEGIN
 IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
   IF OLD.status IN ('COMPLETED','REJECTED','CANCELLED') AND NEW.terminal_reason IS DISTINCT FROM OLD.terminal_reason THEN
     RAISE EXCEPTION 'Terminal Booking reason is immutable';
   END IF;
   RETURN NEW;
 END IF;
 IF OLD.status IN ('COMPLETED','REJECTED','CANCELLED') THEN
   RAISE EXCEPTION 'Terminal Booking state % is immutable',OLD.status;
 END IF;
 ok := CASE
  WHEN OLD.status='PENDING' AND NEW.status IN ('CONFIRMED','VENUE_RESPONSE_EXPIRED') THEN true
  WHEN OLD.status='PENDING' AND NEW.status='REJECTED' THEN r='VENUE_REJECTED'
  WHEN OLD.status='PENDING' AND NEW.status='CANCELLED' THEN r IN ('CUSTOMER_CANCELLED','USER_BANNED','VENUE_OPENING_HOURS_CHANGED','VENUE_CLOSURE',
       'VENUE_EMERGENCY_CLOSURE','RESOURCE_EMERGENCY_OUTAGE','RESOURCE_OWNER_REVIEW_REQUIRED','VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY',
       'RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY','ADMIN_CANCELLED','ADMIN_CORRECTION')
  WHEN OLD.status='VENUE_RESPONSE_EXPIRED' AND NEW.status='AWAITING_CUSTOMER_CONFIRMATION' THEN true
  WHEN OLD.status='VENUE_RESPONSE_EXPIRED' AND NEW.status='REJECTED' THEN r='REJECTED_AFTER_EXPIRY'
  WHEN OLD.status='VENUE_RESPONSE_EXPIRED' AND NEW.status='CANCELLED' THEN r IN ('RESOURCE_OWNER_REVIEW_REQUIRED','RESOURCE_EMERGENCY_OUTAGE',
       'VENUE_EMERGENCY_CLOSURE','VENUE_ACCOUNT_DISABLED','VENUE_DID_NOT_RESPOND_IN_TIME','ADMIN_CANCELLED','ADMIN_CORRECTION')
  WHEN OLD.status='AWAITING_CUSTOMER_CONFIRMATION' AND NEW.status='CONFIRMED' THEN true
  WHEN OLD.status='AWAITING_CUSTOMER_CONFIRMATION' AND NEW.status='CANCELLED' THEN r IN ('CUSTOMER_DECLINED_LATE_CONFIRM','CUSTOMER_RECONFIRM_TIMEOUT',
       'VENUE_ACCOUNT_DISABLED','USER_BANNED','VENUE_OPENING_HOURS_CHANGED','VENUE_CLOSURE','VENUE_EMERGENCY_CLOSURE','RESOURCE_EMERGENCY_OUTAGE',
       'RESOURCE_OWNER_REVIEW_REQUIRED','LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY','ADMIN_CANCELLED','ADMIN_CORRECTION')
  WHEN OLD.status='CONFIRMED' AND NEW.status IN ('ACTIVE','NO_SHOW') THEN NOT (NEW.status='NO_SHOW' AND NEW.source='MANUAL')
  WHEN OLD.status='CONFIRMED' AND NEW.status='CANCELLED' THEN
       (NEW.source='ONLINE' AND r IN ('CUSTOMER_CANCELLED','VENUE_CANCELLED_CONFIRMED_BOOKING','USER_BANNED'))
    OR (NEW.source='MANUAL' AND r='VENUE_CANCELLED_MANUAL_BOOKING')
    OR r IN ('VENUE_OPENING_HOURS_CHANGED','VENUE_CLOSURE','VENUE_EMERGENCY_CLOSURE','RESOURCE_EMERGENCY_OUTAGE','RESOURCE_OWNER_REVIEW_REQUIRED',
             'SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR','ADMIN_CANCELLED','ADMIN_CORRECTION')
  WHEN OLD.status='ACTIVE' AND NEW.status='COMPLETED' THEN true
  WHEN OLD.status='ACTIVE' AND NEW.status='NO_SHOW' THEN NEW.source='ONLINE'
  WHEN OLD.status='ACTIVE' AND NEW.status='CANCELLED' THEN r IN ('VENUE_EMERGENCY_CLOSURE','RESOURCE_EMERGENCY_OUTAGE','RESOURCE_OWNER_REVIEW_REQUIRED',
       'ADMIN_CANCELLED','ADMIN_CORRECTION')
    OR (NEW.source='MANUAL' AND r='VENUE_CANCELLED_MANUAL_BOOKING')  -- PCR-007; actor checked at commit (below)
  WHEN OLD.status='NO_SHOW' AND NEW.status='CANCELLED' THEN r='ADMIN_NO_SHOW_REVERSAL'
  WHEN OLD.status='NO_SHOW' AND NEW.status='COMPLETED' THEN true
  ELSE false END;
 IF NOT COALESCE(ok,false) THEN
   RAISE EXCEPTION 'Invalid Booking transition % -> % (reason %)',OLD.status,NEW.status,COALESCE(r,'none');
 END IF;
 -- Product §29.1: Customer cancellation only while now < startAt.
 IF r='CUSTOMER_CANCELLED' AND now() >= OLD.start_at THEN
   RAISE EXCEPTION 'Customer cancellation is disabled at/after Start Time';
 END IF;
 -- FRS §2.1: NO_SHOW reversal -> CANCELLED before original endAt, -> COMPLETED after it.
 IF OLD.status='NO_SHOW' AND NEW.status='CANCELLED' AND now() >= OLD.end_at THEN
   RAISE EXCEPTION 'No-show reversal after original endAt resolves to COMPLETED';
 END IF;
 IF OLD.status='NO_SHOW' AND NEW.status='COMPLETED' AND now() < OLD.end_at THEN
   RAISE EXCEPTION 'No-show reversal before original endAt resolves to CANCELLED/ADMIN_NO_SHOW_REVERSAL';
 END IF;
 RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION booking.assert_manual_active_cancel_provenance()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    b booking.booking%ROWTYPE;
    v_transitions integer;
    v_matching integer;
BEGIN
    SELECT * INTO b FROM booking.booking WHERE id = NEW.id;
    IF NOT FOUND OR b.source <> 'MANUAL' OR b.status <> 'CANCELLED'
       OR b.terminal_reason IS DISTINCT FROM 'VENUE_CANCELLED_MANUAL_BOOKING' THEN
        RETURN NULL;
    END IF;

    SELECT count(*),
           count(*) FILTER (WHERE h.terminal_reason = 'VENUE_CANCELLED_MANUAL_BOOKING'
                              AND h.occurred_at = b.terminal_at
                              AND h.actor_type = 'VENUE'
                              AND va.id IS NOT NULL)
      INTO v_transitions, v_matching
      FROM booking.booking_history h
      LEFT JOIN iam.venue_account va ON va.id = h.actor_id AND va.venue_id = b.venue_id
     WHERE h.booking_id = b.id
       AND h.entry_kind = 'STATUS_TRANSITION'
       AND h.from_status = 'ACTIVE'
       AND h.to_status = 'CANCELLED';

    IF v_transitions <> 1 OR v_matching <> 1 THEN
        RAISE EXCEPTION 'ACTIVE Manual Booking cancellation requires exactly one matching Venue Booking History transition (found %, matching %)',
            v_transitions, v_matching;
    END IF;
    RETURN NULL;
END;
$function$;

REVOKE ALL ON FUNCTION booking.assert_manual_active_cancel_provenance() FROM PUBLIC;

COMMENT ON FUNCTION booking.assert_manual_active_cancel_provenance() IS E'PCR-007 (v1.1.34): at commit, an ACTIVE -> CANCELLED / VENUE_CANCELLED_MANUAL_BOOKING Manual Booking has exactly one ACTIVE -> CANCELLED history row with that reason, occurred_at = terminal_at, actor VENUE and an account of the booking venue. Verifies recorded actor provenance, not an independently authenticated identity.';

DROP TRIGGER IF EXISTS tr_booking_manual_active_cancel_provenance ON booking.booking;
CREATE CONSTRAINT TRIGGER tr_booking_manual_active_cancel_provenance
AFTER UPDATE OF status ON booking.booking
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
WHEN (OLD.status = 'ACTIVE' AND NEW.status = 'CANCELLED' AND NEW.source = 'MANUAL'
      AND NEW.terminal_reason = 'VENUE_CANCELLED_MANUAL_BOOKING')
EXECUTE FUNCTION booking.assert_manual_active_cancel_provenance();
