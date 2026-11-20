-- =====================================================================================================
-- E7-82 / physical model v1.1.45 — an Emergency is dated the command instant (FR-OPS-06, FR-OPS-09). For Product
-- Owner review.
--
-- Before: operations.guard_emergency_lifecycle set declared_at (and ended_at on ENDED / CONVERTED) to
-- statement_timestamp(), the instant of the INSERT / UPDATE statement itself. Every other write of the same command is
-- dated the command instant tournament.begin_command captured (tournament.command_now(), arch 04 section 2.1): the
-- cascade's Booking terminal_at and history, released reservations, the Tournament history row and a Tournament
-- Resource's removed_at. A statement always runs after begin_command, so the Emergency was always declared a little
-- AFTER its own cascade: tournament.highest_current_cancellation_reason (declared_at <= occurred_at) never saw it in
-- force, and the all-or-nothing cascade of FR-OPS-08 / FR-OPS-09 could never cancel a Tournament
-- ("Condition-driven Tournament reason EMERGENCY_CLOSURE lacks matching highest-priority producer") nor remove a
-- Resource from an ACTIVE one ("... requires matching Resource Emergency evidence").
--
-- 1. operations.emergency_command_now(): the command instant when the transaction runs inside a trusted Tournament
--    command context (tournament.command_now()), else statement_timestamp() - so a write outside a command is dated as
--    before.
-- 2. operations.guard_emergency_lifecycle: its V202610171400 definition (Operating Session supersession) with
--    declared_at and ended_at taken from (1). "Start = NOW only, never scheduled" (FR-OPS-06) holds as before: the
--    command instant is the database's own now of the declaring command, never a client value. The
--    AWAITING_CONFIRMATION and extension checks keep statement_timestamp().
--
-- No table, column, constraint, trigger or grant changes; tr_00_guard_emergency_lifecycle keeps calling the same
-- function. No backfill and no upgrade seed: rows already stored keep their instants.
-- =====================================================================================================
CREATE OR REPLACE FUNCTION operations.emergency_command_now()
RETURNS timestamptz
LANGUAGE plpgsql
VOLATILE
AS $function$
BEGIN
    IF to_regclass('pg_temp.e7gz_tournament_command_context') IS NOT NULL THEN
        BEGIN
            RETURN tournament.command_now();
        EXCEPTION WHEN raise_exception THEN
            -- a context of another transaction, or one that is not trusted: not this command's instant
            RETURN statement_timestamp();
        END;
    END IF;
    RETURN statement_timestamp();
END;
$function$;

CREATE OR REPLACE FUNCTION operations.guard_emergency_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_resource_venue uuid;
    v_session_end timestamptz;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'Emergency must be declared in ACTIVE status';
        END IF;
        NEW.declared_at := operations.emergency_command_now();
        NEW.ended_at := NULL;
        NEW.converted_to := NULL;
    ELSE
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.scope IS DISTINCT FROM OLD.scope
           OR NEW.venue_id IS DISTINCT FROM OLD.venue_id
           OR NEW.resource_id IS DISTINCT FROM OLD.resource_id
           OR NEW.declared_at IS DISTINCT FROM OLD.declared_at
           OR NEW.reason IS DISTINCT FROM OLD.reason THEN
            RAISE EXCEPTION 'Emergency declaration identity/scope/reason are immutable after declaration';
        END IF;

        IF OLD.status IN ('ENDED','CONVERTED') THEN
            RAISE EXCEPTION 'Terminal Emergency state is immutable';
        END IF;

        IF OLD.status = 'ACTIVE' AND NEW.status NOT IN ('ACTIVE','AWAITING_CONFIRMATION','ENDED','CONVERTED') THEN
            RAISE EXCEPTION 'Invalid Emergency transition from ACTIVE to %', NEW.status;
        ELSIF OLD.status = 'AWAITING_CONFIRMATION' AND NEW.status NOT IN ('ACTIVE','AWAITING_CONFIRMATION','ENDED','CONVERTED') THEN
            RAISE EXCEPTION 'Invalid Emergency transition from AWAITING_CONFIRMATION to %', NEW.status;
        END IF;

        -- Canonical meaning: AWAITING_CONFIRMATION exists only after the current Expected End.
        IF NEW.status = 'AWAITING_CONFIRMATION' AND statement_timestamp() < NEW.expected_end_at THEN
            RAISE EXCEPTION 'AWAITING_CONFIRMATION requires Expected End to have been reached';
        END IF;

        -- Product §42.4 permits Extend after Expected End. That action may return AWAITING -> ACTIVE
        -- only by moving Expected End forward beyond NOW; it never clears the Selling Block.
        IF OLD.status = 'AWAITING_CONFIRMATION' AND NEW.status = 'ACTIVE' THEN
            IF NEW.expected_end_at <= OLD.expected_end_at OR NEW.expected_end_at <= statement_timestamp() THEN
                RAISE EXCEPTION 'AWAITING_CONFIRMATION may return to ACTIVE only through a valid Emergency extension';
            END IF;
        END IF;

        IF OLD.status IN ('ACTIVE','AWAITING_CONFIRMATION')
           AND NEW.status IN ('ENDED','CONVERTED')
           AND NEW.ended_at IS NULL THEN
            NEW.ended_at := operations.emergency_command_now();
        END IF;
    END IF;

    -- Global lock order: Venue first, then Resource(s).
    PERFORM 1 FROM venue.venue v WHERE v.id = NEW.venue_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Venue % does not exist', NEW.venue_id;
    END IF;

    IF NEW.scope = 'RESOURCE' THEN
        SELECT r.venue_id INTO v_resource_venue
          FROM resource.resource r
         WHERE r.id = NEW.resource_id
         FOR UPDATE;
        IF NOT FOUND OR v_resource_venue IS DISTINCT FROM NEW.venue_id THEN
            RAISE EXCEPTION 'RESOURCE Emergency must reference a Resource belonging to the same Venue';
        END IF;
    ELSE
        -- Venue-wide cascades serialize all affected Resources in deterministic UUID order.
        PERFORM 1 FROM resource.resource r
         WHERE r.venue_id = NEW.venue_id
         ORDER BY r.id
         FOR UPDATE;
    END IF;

    SELECT upper(s.session_period) INTO v_session_end
      FROM venue.operating_session s
     WHERE s.venue_id = NEW.venue_id
       AND NEW.declared_at <@ s.session_period
       AND s.superseded_at IS NULL  -- v1.1.37: the current session, never a superseded historical one
     ORDER BY lower(s.session_period)
     LIMIT 1;
    IF v_session_end IS NULL THEN
        RAISE EXCEPTION 'Emergency declaration must occur inside a materialized Operating Session';
    END IF;
    IF NEW.expected_end_at > v_session_end THEN
        RAISE EXCEPTION 'Emergency expected end may not exceed the current Operating Session end';
    END IF;

    NEW.declared_period := tstzrange(NEW.declared_at, NEW.expected_end_at, '[)');
    RETURN NEW;
END;
$function$;
