-- =====================================================================================================
-- E7-71 / physical model v1.1.42 — a Ban does not stop a late start (Product Owner decision 19 Oct 2026, the E7-59 /
-- E7-60 review; Spec v5 section 48.6 does not cover it). For Product Owner review.
--
-- Before: trust.guard_booking_ban_eligibility (tr_05_booking_ban_eligibility) refused every move of a banned
-- customer's Online Booking into PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED or ACTIVE - so also
-- CONFIRMED -> ACTIVE. The Ban cascade (trust.register_ban_cascade_targets) cancels only CONFIRMED bookings whose startAt
-- is in the future; a booking still CONFIRMED after its startAt because its T4 is late is treated as started and
-- continues (FR-TRS-20: ACTIVE continues). Its late T4 then failed on the guard and the booking could never start.
--
-- 1. trust.guard_booking_ban_eligibility: its V1 definition plus one block - CONFIRMED -> ACTIVE is a start, not a new
--    or stronger commitment, and is let through. Creation, a venue accept (PENDING -> CONFIRMED) and a Late Confirm
--    accept (AWAITING -> CONFIRMED) stay refused for a banned customer (FR-ACC-17).
--
-- No table, column, constraint, trigger or grant changes; the trigger keeps calling the same function. No backfill and
-- no upgrade seed: the change only lets a transition through that was refused before.
-- =====================================================================================================
CREATE OR REPLACE FUNCTION trust.guard_booking_ban_eligibility()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $function$
DECLARE v_now timestamptz;
BEGIN
    IF NEW.source <> 'ONLINE' OR NEW.customer_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    IF NEW.status NOT IN ('PENDING','AWAITING_CUSTOMER_CONFIRMATION','CONFIRMED','ACTIVE') THEN
        RETURN NEW;
    END IF;
    -- v1.1.42 (E7-71, PO decision 19 Oct 2026): a start is not a new or stronger commitment - a CONFIRMED booking the
    -- Ban cascade left (its startAt was not in the future) is treated as started and its late T4 still starts it.
    IF TG_OP = 'UPDATE' AND OLD.status = 'CONFIRMED' AND NEW.status = 'ACTIVE' THEN
        RETURN NEW;
    END IF;

    PERFORM 1 FROM iam.user_commitment_guard g WHERE g.user_id = NEW.customer_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Online Booking commitment requires the Customer commitment guard';
    END IF;
    IF to_regclass('pg_temp.e7gz_tournament_command_context') IS NOT NULL THEN
        v_now:=trust.command_now(NEW.customer_id);
    ELSE
        -- Booking-only writer: it must obey its existing root-lock contract.
        v_now:=clock_timestamp();
    END IF;
    IF EXISTS (
        SELECT 1 FROM trust.ban_instance b
         WHERE b.user_id = NEW.customer_id
           AND b.status = 'ACTIVE'
           AND b.activated_at <= v_now
           AND b.ends_at > v_now
    ) THEN
        RAISE EXCEPTION 'Banned User may not create or strengthen an Online Booking commitment';
    END IF;
    RETURN NEW;
END;
$function$;
