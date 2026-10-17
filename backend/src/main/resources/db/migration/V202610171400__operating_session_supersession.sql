-- =====================================================================================================
-- E7-113 / physical model v1.1.36 — a referenced Operating Session the hours no longer generate is superseded, and
-- the operating day gets its current session beside it (Amr's engineering decision for E7-113, 17 Oct 2026; FR-VEN-22,
-- FR-VEN-23 step 8, FR-VEN-24, FR-BOK-05, FR-BOK-08/09, FR-MAN-03). For Product Owner review.
--
-- Before: one session per venue and operating day (uq_operating_session_day) and no two overlapping sessions of a
-- venue (ex_session_no_overlap). A session a booking references is a historical snapshot that never changes
-- (venue.guard_operating_session), so after an hours change its day kept the OLD session: no session for the new
-- hours could be created (new Online bookings inside the new hours were refused), and a Manual walk-in inside the old
-- window had to snapshot the old session, so a later unrelated hours change treated it as affected.
--
-- 1. venue.operating_session.superseded_at (nullable): set once, at the hours change that stops generating the
--    session, on a REFERENCED session only (an unreferenced one is deleted and regenerated, as before). The row, its
--    bounds and every booking or tournament reference stay. ck_operating_session_superseded_after_generation: it is
--    never before the row's own generation instant.
-- 2. uq_operating_session_day becomes a partial unique index under the same name: one CURRENT session per venue and
--    operating day (superseded_at IS NULL). ex_session_no_overlap is re-added under the same name, columns, operators
--    and method, its predicate narrowed to current sessions. Superseded history may overlap the current session.
-- 3. venue.guard_operating_session: as V1, plus - a superseded session never changes again (no restamp, no
--    un-superseding), and only a referenced session can be superseded.
-- 4. A new booking never snapshots superseded history (booking.validate_booking_insert_snapshot), and an Online
--    Booking's intake window (booking.validate_online_booking_intake, the v1.1.28 definition) and an Emergency's
--    current session (operations.guard_emergency_lifecycle) are read from current sessions only. A Manual Booking is
--    "inside a published Operating Session" only when a current session contains it (FR-MAN-03: otherwise it is an
--    out-of-hours walk-in, with no snapshot). Each function is its previous definition plus that one filter.
--
-- No backfill: superseded_at starts NULL on every existing row. A pinned row written before this change (a referenced
-- future session the hours stopped generating) cannot be told safely from SQL alone from a referenced row that still
-- matches the hours (before E7-113 such a row was not restamped), so none is guessed here. The application's next
-- regeneration of the venue - the nightly horizon extension or the next hours change - supersedes a pinned row through
-- the guarded path and creates the current session (SessionPlan). Until then such a row behaves as it did before.
--
-- Unchanged on purpose: every other table, column, constraint, trigger and grant; booking reads by
-- operating_session_id (a booking keeps its own historical session); tournament.assert_tournament_aggregate (reads its
-- tournament's session by id); the guard identifier mapping (ex_session_no_overlap keeps its name).
-- Platform-lane table (Venue) with Engine-lane booking guards: needs both owners' review.
-- =====================================================================================================
ALTER TABLE venue.operating_session
    ADD COLUMN superseded_at timestamp with time zone;

ALTER TABLE venue.operating_session
    ADD CONSTRAINT ck_operating_session_superseded_after_generation
    CHECK (superseded_at IS NULL OR superseded_at >= generated_at);

COMMENT ON COLUMN venue.operating_session.superseded_at IS E'v1.1.36: when an hours change stopped generating this referenced session; NULL = the current session of its day. A superseded session is history: it is never offered to a new booking and never changes again.';

ALTER TABLE venue.operating_session
    DROP CONSTRAINT uq_operating_session_day;

CREATE UNIQUE INDEX uq_operating_session_day ON venue.operating_session
    USING btree (venue_id, operating_day)
    WHERE superseded_at IS NULL;

ALTER TABLE venue.operating_session
    DROP CONSTRAINT ex_session_no_overlap;

ALTER TABLE venue.operating_session
    ADD CONSTRAINT ex_session_no_overlap
    EXCLUDE USING gist (venue_id WITH =, session_period WITH &&)
    WHERE (superseded_at IS NULL);

COMMENT ON CONSTRAINT ex_session_no_overlap ON venue.operating_session IS E'No two current sessions of a venue overlap (v1.1.36: superseded history is exempt).';

CREATE OR REPLACE FUNCTION venue.guard_operating_session()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_referenced boolean;
BEGIN
 SELECT EXISTS(SELECT 1 FROM booking.booking b WHERE b.operating_session_id=OLD.id)
     OR EXISTS(SELECT 1 FROM tournament.tournament t WHERE t.operating_session_id=OLD.id) INTO v_referenced;
 -- v1.1.36: supersession is set once, on a referenced session only, and a superseded session never changes again.
 IF TG_OP='UPDATE' AND OLD.superseded_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
   RAISE EXCEPTION 'A superseded Operating Session is history and never changes';
 END IF;
 IF TG_OP='UPDATE' AND NEW.superseded_at IS NOT NULL AND OLD.superseded_at IS NULL AND NOT v_referenced THEN
   RAISE EXCEPTION 'Only a referenced Operating Session is superseded; an unreferenced one is regenerated';
 END IF;
 IF NOT v_referenced THEN
   IF TG_OP='DELETE' THEN RETURN OLD; END IF;
   RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'A referenced Operating Session is a historical snapshot and cannot be deleted'; END IF;
 IF NEW.venue_id<>OLD.venue_id OR NEW.operating_day<>OLD.operating_day OR NEW.local_start<>OLD.local_start OR NEW.local_end<>OLD.local_end
    OR NEW.session_period<>OLD.session_period OR NEW.timezone<>OLD.timezone OR NEW.opening_template_id<>OLD.opening_template_id THEN
   RAISE EXCEPTION 'A referenced Operating Session is a historical snapshot; Opening Hours changes never rewrite it';
 END IF;
 RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION booking.validate_booking_insert_snapshot()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    r resource.resource%ROWTYPE;
    os venue.operating_session%ROWTYPE;
BEGIN
    SELECT * INTO r FROM resource.resource WHERE id = NEW.resource_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'booking resource does not exist';
    END IF;

    IF r.venue_id <> NEW.venue_id THEN
        RAISE EXCEPTION 'booking venue_id must match the Resource venue';
    END IF;
    IF r.activity <> NEW.activity THEN
        RAISE EXCEPTION 'booking activity snapshot must match the Resource activity at creation';
    END IF;
    IF r.console_type IS DISTINCT FROM NEW.console_type THEN
        RAISE EXCEPTION 'booking console snapshot must match the Resource console at creation';
    END IF;
    IF r.name IS DISTINCT FROM NEW.resource_name_snapshot THEN
        RAISE EXCEPTION 'booking resource_name_snapshot must match the Resource name at creation';
    END IF;

    IF NEW.operating_session_id IS NOT NULL THEN
        SELECT * INTO os FROM venue.operating_session WHERE id = NEW.operating_session_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'booking operating_session_id does not exist';
        END IF;
        IF os.venue_id <> NEW.venue_id THEN
            RAISE EXCEPTION 'booking Operating Session must belong to the Booking venue';
        END IF;
        IF os.superseded_at IS NOT NULL THEN  -- v1.1.36: a new booking never snapshots superseded history
            RAISE EXCEPTION 'a new booking cannot snapshot a superseded Operating Session';
        END IF;
        IF NOT (os.session_period @> tstzrange(NEW.start_at, NEW.end_at, '[)')) THEN
            RAISE EXCEPTION 'booking interval must fit fully inside the snapshotted Operating Session';
        END IF;
        IF NEW.operating_day IS DISTINCT FROM os.operating_day
           OR NEW.session_local_start IS DISTINCT FROM os.local_start
           OR NEW.session_local_end IS DISTINCT FROM os.local_end
           OR NEW.session_timezone IS DISTINCT FROM os.timezone THEN
            RAISE EXCEPTION 'booking Operating Session snapshot does not match the authoritative session at creation';
        END IF;
        IF NEW.business_date IS DISTINCT FROM os.operating_day THEN
            RAISE EXCEPTION 'booking business_date must equal operating_day when an Operating Session is snapshotted';
        END IF;
    ELSE
        IF NEW.source <> 'MANUAL' THEN
            RAISE EXCEPTION 'Online Booking requires an Operating Session snapshot';
        END IF;
        IF NEW.operating_day IS NOT NULL
           OR NEW.session_local_start IS NOT NULL
           OR NEW.session_local_end IS NOT NULL THEN
            RAISE EXCEPTION 'out-of-hours Manual Booking must not fabricate Operating Session fields';
        END IF;
        IF NEW.session_timezone IS DISTINCT FROM 'Africa/Cairo' THEN
            RAISE EXCEPTION 'out-of-hours Manual Booking must snapshot Africa/Cairo timezone';
        END IF;
        IF NEW.business_date IS DISTINCT FROM (NEW.start_at AT TIME ZONE 'Africa/Cairo')::date THEN
            RAISE EXCEPTION 'out-of-hours Manual Booking business_date must be the local start date';
        END IF;
        IF EXISTS (
            SELECT 1
            FROM venue.operating_session s
            WHERE s.venue_id = NEW.venue_id
              AND s.session_period @> tstzrange(NEW.start_at, NEW.end_at, '[)')
              AND s.superseded_at IS NULL  -- v1.1.36: only a current session is published
        ) THEN
            RAISE EXCEPTION 'Manual Booking inside a published Operating Session must store the normal session snapshot';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION booking.validate_online_booking_intake()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_area_active boolean;
BEGIN
 IF NEW.source<>'ONLINE' THEN RETURN NEW; END IF;
 IF NEW.created_at IS NULL OR NEW.created_at>clock_timestamp() THEN
   RAISE EXCEPTION 'Online Booking created_at must be the server creation instant';
 END IF;
 -- §22 item 11: requested Start at least 15 minutes in the future.
 IF NEW.start_at < NEW.created_at + interval '15 minutes' THEN
   RAISE EXCEPTION 'Online Booking Start must be at least 15 minutes after creation' USING CONSTRAINT = 'gd_online_booking_lead_time';
 END IF;
 -- §23.1: the Venue has 10 minutes to respond.
 IF NEW.status='PENDING' AND NEW.venue_response_expires_at IS DISTINCT FROM NEW.created_at + interval '10 minutes' THEN
   RAISE EXCEPTION 'venueResponseExpiresAt must be creation + 10 minutes';
 END IF;
 -- §21 / §22 items 8-9: created inside a current Operating Session and at least 10 minutes before it ends.
 IF NOT EXISTS(SELECT 1 FROM venue.operating_session s
                WHERE s.venue_id=NEW.venue_id AND s.session_period @> NEW.created_at AND s.superseded_at IS NULL
                  AND NEW.created_at < upper(s.session_period) - interval '10 minutes') THEN
   RAISE EXCEPTION 'Online Booking intake requires a current Operating Session and stops 10 minutes before it ends' USING CONSTRAINT = 'gd_online_booking_intake_window';
 END IF;
 -- §22 item 7: Area ACTIVE.
 SELECT a.is_active INTO v_area_active FROM venue.venue v JOIN geo.area a ON a.id=v.area_id WHERE v.id=NEW.venue_id FOR SHARE OF a;
 IF NOT COALESCE(v_area_active,false) THEN RAISE EXCEPTION 'Online Booking requires the Venue Area to be ACTIVE' USING CONSTRAINT = 'gd_online_booking_area_inactive'; END IF;
 -- §5.1: the stored fee is the global Booking Fee at creation.
 IF NEW.booking_fee_snapshot IS DISTINCT FROM config.effective_numeric('booking.fee',NEW.created_at) THEN
   RAISE EXCEPTION 'booking_fee_snapshot must equal the Global Booking Fee at creation';
 END IF;
 RETURN NEW;
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
        NEW.declared_at := statement_timestamp();
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
            NEW.ended_at := statement_timestamp();
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
       AND s.superseded_at IS NULL  -- v1.1.36: the current session, never a superseded historical one
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
