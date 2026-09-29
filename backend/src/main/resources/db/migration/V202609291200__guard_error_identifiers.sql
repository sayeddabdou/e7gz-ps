-- =====================================================================================================
-- E7-12 / physical model v1.1.28 — stable identifiers on the Product guard exceptions (arch 09 §1.5, ADR-026).
--
-- Each function below is the FINAL definition from V1__baseline.sql (v1.1.27), copied verbatim, with only the
-- marked raises changed. A mapped guard now carries a stable database identifier in the standard PostgreSQL
-- diagnostic field CONSTRAINT (RAISE ... USING CONSTRAINT = 'gd_...'); the API maps that identifier - never the
-- message text - to its ApiErrorCode (ps.e7gz.common.error.DatabaseErrorCodes).
--
-- Unchanged on purpose: every message text, SQLSTATE (P0001 raise_exception), signature, SECURITY DEFINER flag,
-- search_path, language, owner and grant (CREATE OR REPLACE keeps owner and ACL). No table, column, index,
-- constraint, trigger or data change. Internal invariant raises are NOT given an identifier: they stay
-- unexpected failures (HTTP 500 with the correlation ID only).
--
-- Kinds of change (see docs/04-erd/physical/E7GZ-PS-v1.1.27-CHANGE-REPORT.md, v1.1.28):
--   [D] diagnostic metadata only - the same raise at the same point, plus USING CONSTRAINT.
--   [B] condition branch split - one combined IF becomes IF/ELSIF branches over exactly the same conditions,
--       in the same place, raising the same message; only the branch that is a Product rule carries an
--       identifier. The same writes are accepted and rejected (three-valued logic checked branch by branch).
-- Engine-lane functions (booking, inventory, attendance, tournament) need the Engine owner's review.
-- =====================================================================================================

-- ---------- booking.validate_online_booking_intake (Engine lane; final of 1 definition(s) in V1) ----------
-- [D] diagnostic: gd_online_booking_area_inactive, gd_online_booking_intake_window, gd_online_booking_lead_time
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
                WHERE s.venue_id=NEW.venue_id AND s.session_period @> NEW.created_at
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

-- ---------- booking.validate_booking_creation_venue (Engine lane; final of 1 definition(s) in V1) ----------
-- [B] branch split, [D] diagnostic: gd_manual_booking_backdated, gd_online_booking_venue_not_active
CREATE OR REPLACE FUNCTION booking.validate_booking_creation_venue()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_market text; v_account text;
BEGIN
 SELECT v.marketplace_status INTO v_market FROM venue.venue v WHERE v.id=NEW.venue_id FOR SHARE;
 SELECT va.account_status INTO v_account FROM iam.venue_account va WHERE va.venue_id=NEW.venue_id FOR SHARE;
 IF v_account IS DISTINCT FROM 'ENABLED' THEN RAISE EXCEPTION 'Booking creation requires Venue Account ENABLED'; END IF;
 IF NEW.source='ONLINE' THEN
   IF NEW.status<>'PENDING' THEN RAISE EXCEPTION 'An Online Booking is created PENDING'; END IF;
   IF v_market IS DISTINCT FROM 'ACTIVE' THEN RAISE EXCEPTION 'New Online Booking requires Venue Marketplace ACTIVE' USING CONSTRAINT = 'gd_online_booking_venue_not_active'; END IF;
 ELSE
   IF v_market IS NULL OR v_market='ONBOARDING' THEN RAISE EXCEPTION 'Manual Booking is not allowed for an ONBOARDING Venue'; END IF;
   IF NEW.created_at IS NULL OR NEW.created_at>clock_timestamp() THEN
     RAISE EXCEPTION 'Manual Booking Start is now or future, never retroactive';
   ELSIF NEW.start_at < NEW.created_at THEN
     RAISE EXCEPTION 'Manual Booking Start is now or future, never retroactive' USING CONSTRAINT = 'gd_manual_booking_backdated';
   END IF;
   IF (NEW.status='ACTIVE') <> (NEW.start_at = NEW.created_at) OR NEW.status NOT IN ('CONFIRMED','ACTIVE') THEN
     RAISE EXCEPTION 'Manual Booking is created CONFIRMED (future) or ACTIVE (current walk-in, startAt = creation)';
   END IF;
 END IF;
 RETURN NEW;
END;
$function$;

-- ---------- inventory.guard_resource_reservation_cross_class (Engine lane; final of 2 definition(s) in V1) ----------
-- [D] diagnostic: gd_reservation_conflict, gd_reservation_conflict_booking, gd_reservation_conflict_closure, gd_reservation_conflict_late_confirm, gd_reservation_conflict_maintenance, gd_reservation_conflict_tournament
CREATE OR REPLACE FUNCTION inventory.guard_resource_reservation_cross_class()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    v_conflict boolean;
BEGIN
    IF NOT NEW.active_flag THEN
        RETURN NEW;
    END IF;

    -- v1.1.27: this cross-class check re-reads committed rows after the Resource lock. That is race-safe under
    -- READ COMMITTED (fresh snapshot per statement) and SERIALIZABLE (SSI), but not under REPEATABLE READ.
    IF current_setting('transaction_isolation') = 'repeatable read' THEN
        RAISE EXCEPTION 'Inventory writes must not run under REPEATABLE READ; use READ COMMITTED';
    END IF;

    -- Serialize all cross-class decisions for the Resource.
    PERFORM 1 FROM resource.resource WHERE id = NEW.resource_id FOR UPDATE;

    IF NEW.reservation_type = 'MAINTENANCE' THEN
        -- New Maintenance may overlap Maintenance only; it may not overlap Closure or commitments.
        SELECT EXISTS (
            SELECT 1
              FROM inventory.resource_reservation r
             WHERE r.resource_id = NEW.resource_id
               AND r.id IS DISTINCT FROM NEW.id
               AND r.active_flag
               AND r.planned_range && NEW.planned_range
               AND r.reservation_type <> 'MAINTENANCE'
        ) INTO v_conflict;
    ELSIF NEW.reservation_type = 'CLOSURE' THEN
        -- Closure may overlap existing Maintenance, but never commitments or another active Closure.
        SELECT EXISTS (
            SELECT 1
              FROM inventory.resource_reservation r
             WHERE r.resource_id = NEW.resource_id
               AND r.id IS DISTINCT FROM NEW.id
               AND r.active_flag
               AND r.planned_range && NEW.planned_range
               AND r.reservation_type <> 'MAINTENANCE'
        ) INTO v_conflict;
    ELSE
        -- Booking / Late Confirm / Tournament commitments may overlap nothing active, including Maintenance.
        SELECT EXISTS (
            SELECT 1
              FROM inventory.resource_reservation r
             WHERE r.resource_id = NEW.resource_id
               AND r.id IS DISTINCT FROM NEW.id
               AND r.active_flag
               AND r.planned_range && NEW.planned_range
        ) INTO v_conflict;
    END IF;

    IF v_conflict THEN
        RAISE EXCEPTION 'resource reservation conflict for resource % in interval %', NEW.resource_id, NEW.planned_range
            USING CONSTRAINT = CASE NEW.reservation_type
                WHEN 'BOOKING' THEN 'gd_reservation_conflict_booking'
                WHEN 'LATE_CONFIRM_HOLD' THEN 'gd_reservation_conflict_late_confirm'
                WHEN 'TOURNAMENT_APPROVAL_HOLD' THEN 'gd_reservation_conflict_tournament'
                WHEN 'TOURNAMENT_HOLD' THEN 'gd_reservation_conflict_tournament'
                WHEN 'MAINTENANCE' THEN 'gd_reservation_conflict_maintenance'
                WHEN 'CLOSURE' THEN 'gd_reservation_conflict_closure'
                ELSE 'gd_reservation_conflict' END;
    END IF;

    RETURN NEW;
END;
$function$;

-- ---------- attendance.validate_attendance_action_write (Engine lane; final of 1 definition(s) in V1) ----------
-- [D] diagnostic: gd_check_in_window, gd_late_present_window, gd_no_show_window
CREATE OR REPLACE FUNCTION attendance.validate_attendance_action_write()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    b booking.booking%ROWTYPE;
    v_check_added boolean;
    v_late_added boolean;
    v_no_show_added boolean;
    v_action_at timestamptz;
    v_actor_venue uuid;
BEGIN
    SELECT * INTO b FROM booking.booking WHERE id = NEW.booking_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Attendance Booking does not exist';
    END IF;

    IF TG_OP = 'INSERT' THEN
        v_check_added := NEW.check_in_at IS NOT NULL;
        v_late_added := NEW.late_present_at IS NOT NULL;
        v_no_show_added := NEW.no_show_at IS NOT NULL;
    ELSE
        v_check_added := NEW.check_in_at IS NOT NULL AND OLD.check_in_at IS NULL;
        v_late_added := NEW.late_present_at IS NOT NULL AND OLD.late_present_at IS NULL;
        v_no_show_added := NEW.no_show_at IS NOT NULL AND OLD.no_show_at IS NULL;
    END IF;

    IF b.source = 'MANUAL' THEN
        IF NEW.check_in_at IS NOT NULL OR NEW.late_present_at IS NOT NULL OR NEW.no_show_at IS NOT NULL THEN
            RAISE EXCEPTION 'Manual Booking does not use Product Attendance evidence';
        END IF;
        IF NEW.result IS NOT NULL AND NEW.result <> 'NOT_APPLICABLE' THEN
            RAISE EXCEPTION 'Manual Booking Attendance Result must be NOT_APPLICABLE';
        END IF;
    END IF;

    IF v_check_added OR v_late_added OR v_no_show_added THEN
        IF b.source <> 'ONLINE' OR b.customer_id IS NULL THEN
            RAISE EXCEPTION 'Venue attendance actions require an Online customer Booking';
        END IF;
        IF NEW.recorded_by_venue_account_id IS NULL THEN
            RAISE EXCEPTION 'Venue attendance action requires recorded_by_venue_account_id';
        END IF;
        SELECT venue_id INTO v_actor_venue
          FROM iam.venue_account
         WHERE id = NEW.recorded_by_venue_account_id;
        IF NOT FOUND OR v_actor_venue IS DISTINCT FROM b.venue_id THEN
            RAISE EXCEPTION 'Attendance Venue Account must belong to the Booking Venue';
        END IF;
    END IF;

    IF v_check_added THEN
        v_action_at := NEW.check_in_at;
        IF NOT (v_action_at >= b.start_at AND v_action_at < b.start_at + interval '15 minutes') THEN
            RAISE EXCEPTION 'Check-in must be within [startAt, startAt + 15 minutes)' USING CONSTRAINT = 'gd_check_in_window';
        END IF;
        IF EXISTS (
            SELECT 1 FROM iam.venue_account_restriction_interval r
             WHERE r.venue_id = b.venue_id
               AND r.disabled_at <= v_action_at
               AND (r.reenabled_at IS NULL OR v_action_at < r.reenabled_at)
        ) THEN
            RAISE EXCEPTION 'Check-in may not be recorded during a Venue Account disable interval';
        END IF;
    END IF;

    IF v_late_added THEN
        v_action_at := NEW.late_present_at;
        IF NOT (v_action_at >= b.start_at + interval '15 minutes' AND v_action_at < b.end_at) THEN
            RAISE EXCEPTION 'Late Present must be within [startAt + 15 minutes, endAt)' USING CONSTRAINT = 'gd_late_present_window';
        END IF;
        IF EXISTS (
            SELECT 1 FROM iam.venue_account_restriction_interval r
             WHERE r.venue_id = b.venue_id
               AND r.disabled_at <= v_action_at
               AND (r.reenabled_at IS NULL OR v_action_at < r.reenabled_at)
        ) THEN
            RAISE EXCEPTION 'Late Present may not be recorded during a Venue Account disable interval';
        END IF;
    END IF;

    IF v_no_show_added THEN
        v_action_at := NEW.no_show_at;
        IF NOT (v_action_at >= b.start_at + interval '15 minutes' AND v_action_at < b.end_at) THEN
            RAISE EXCEPTION 'No-show must be within [startAt + 15 minutes, endAt)' USING CONSTRAINT = 'gd_no_show_window';
        END IF;
        IF EXISTS (
            SELECT 1 FROM iam.venue_account_restriction_interval r
             WHERE r.venue_id = b.venue_id
               AND r.disabled_at <= v_action_at
               AND (r.reenabled_at IS NULL OR v_action_at < r.reenabled_at)
        ) THEN
            RAISE EXCEPTION 'No-show may not be recorded during a Venue Account disable interval';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

-- ---------- tournament.prepare_tournament_insert (Engine lane; final of 4 definition(s) in V1) ----------
-- [B] branch split, [D] diagnostic: gd_tournament_creation_cutoff, gd_tournament_creator_banned, gd_tournament_creator_deleted, gd_tournament_creator_not_verified_player, gd_tournament_creator_unverified, gd_tournament_retry_cooldown, gd_tournament_venue_not_active, gd_tournament_venue_selling_block
CREATE OR REPLACE FUNCTION tournament.prepare_tournament_insert()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'pg_temp'
AS $function$
DECLARE
    v_now timestamptz;
    v_marketplace text;
    v_account text;
    v_phone_verified boolean;
    v_anonymised_at timestamptz;
    v_vp_status text;
    v_last_terminal timestamptz;
BEGIN
    v_now := tournament.require_command_scope(NEW.id, NEW.venue_id, NULL, NEW.creator_user_id);
    PERFORM tournament.mark_created_in_command(NEW.id);
    NEW.created_at := v_now;
    NEW.end_at := NEW.start_at + make_interval(mins => NEW.nominal_minutes);
    NEW.checkpoint_at := NEW.start_at - interval '1 hour';
    NEW.version := 0;

    IF NEW.host_type = 'PLAYER' THEN
        NEW.status := 'PENDING_VENUE_APPROVAL';
        NEW.approval_expires_at := LEAST(NEW.created_at + interval '10 minutes', NEW.checkpoint_at);  -- v1.1.27: FR-TRN-10 / Product §37.2
    ELSIF NEW.host_type = 'VENUE' THEN
        NEW.status := 'WAITING_FOR_PLAYERS';
        NEW.approval_expires_at := NULL;
    END IF;

    SELECT v.marketplace_status INTO v_marketplace
      FROM venue.venue v WHERE v.id = NEW.venue_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'New Tournament creation requires Venue Marketplace ACTIVE';
    ELSIF v_marketplace <> 'ACTIVE' THEN
        RAISE EXCEPTION 'New Tournament creation requires Venue Marketplace ACTIVE' USING CONSTRAINT = 'gd_tournament_venue_not_active';
    END IF;

    SELECT va.account_status INTO v_account
      FROM iam.venue_account va WHERE va.venue_id = NEW.venue_id;
    IF v_account IS DISTINCT FROM 'ENABLED' THEN
        RAISE EXCEPTION 'New Tournament creation requires Venue Account ENABLED';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM venue.venue_activity a WHERE a.venue_id = NEW.venue_id AND a.activity = NEW.activity) THEN
        RAISE EXCEPTION 'Tournament activity is not configured for the selected Venue';
    END IF;
    IF EXISTS (SELECT 1 FROM venue.venue_selling_block b WHERE b.venue_id = NEW.venue_id AND b.active) THEN
        RAISE EXCEPTION 'New Tournament creation is blocked by a Venue Operational Selling Block' USING CONSTRAINT = 'gd_tournament_venue_selling_block';
    END IF;

    -- v1.1.27: Product §36.3 creation cutoff requestTime <= startAt - 2 hours for BOTH host types.
    IF NEW.created_at > NEW.start_at - interval '2 hours' THEN
        RAISE EXCEPTION 'Tournament creation cutoff is startAt - 2 hours' USING CONSTRAINT = 'gd_tournament_creation_cutoff';
    END IF;
    -- v1.1.27: Tournament Creation Fee snapshot must equal the configured fee effective at creation (Product §5.3, FR-ADM-10).
    IF NEW.creation_fee_snapshot IS DISTINCT FROM config.effective_numeric('tournament.creation_fee', NEW.created_at) THEN
        RAISE EXCEPTION 'Tournament creation_fee_snapshot must equal the configured Tournament Creation Fee at creation';
    END IF;

    IF NEW.host_type = 'PLAYER' THEN
        SELECT u.phone_verified, u.anonymised_at
          INTO v_phone_verified, v_anonymised_at
          FROM iam.user_account u
         WHERE u.id = NEW.creator_user_id
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Player-hosted Tournament creator must be an Account-Verified active User';
        ELSIF v_anonymised_at IS NOT NULL THEN
            RAISE EXCEPTION 'Player-hosted Tournament creator must be an Account-Verified active User' USING CONSTRAINT = 'gd_tournament_creator_deleted';
        ELSIF NOT v_phone_verified THEN
            RAISE EXCEPTION 'Player-hosted Tournament creator must be an Account-Verified active User' USING CONSTRAINT = 'gd_tournament_creator_unverified';
        END IF;
        SELECT p.vp_operational_status INTO v_vp_status
          FROM trust.trust_projection p WHERE p.user_id = NEW.creator_user_id;
        IF v_vp_status IS DISTINCT FROM 'VERIFIED' THEN
            RAISE EXCEPTION 'Only operational VERIFIED may create a Player-hosted Tournament' USING CONSTRAINT = 'gd_tournament_creator_not_verified_player';
        END IF;
        IF EXISTS (
            SELECT 1 FROM trust.ban_instance b
             WHERE b.user_id = NEW.creator_user_id
               AND b.status = 'ACTIVE'
               AND b.activated_at <= NEW.created_at
               AND b.ends_at > NEW.created_at
        ) THEN
            RAISE EXCEPTION 'Banned User may not create a Tournament' USING CONSTRAINT = 'gd_tournament_creator_banned';
        END IF;

        SELECT MAX(h.occurred_at) INTO v_last_terminal
          FROM tournament.tournament t0
          JOIN tournament.tournament_history h ON h.tournament_id = t0.id
         WHERE t0.host_type = 'PLAYER'
           AND t0.creator_user_id = NEW.creator_user_id
           AND t0.venue_id = NEW.venue_id
           AND t0.start_at = NEW.start_at
           AND t0.end_at = NEW.end_at
           AND (   (t0.status = 'REJECTED' AND h.to_status = 'REJECTED')
                OR (t0.status = 'CANCELLED' AND h.to_status = 'CANCELLED'
                    AND h.terminal_reason = 'VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME'));  -- v1.1.27: Product §37.3 REJECTED or EXPIRED
        IF v_last_terminal IS NOT NULL AND NEW.created_at < v_last_terminal + interval '5 minutes' THEN
            RAISE EXCEPTION 'Creator must wait 5 minutes before re-requesting the same Venue/time interval' USING CONSTRAINT = 'gd_tournament_retry_cooldown';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

-- ---------- tournament.guard_tournament_participant (Engine lane; final of 7 definition(s) in V1) ----------
-- [D] diagnostic: gd_tournament_capacity_full
CREATE OR REPLACE FUNCTION tournament.guard_tournament_participant()
 RETURNS trigger
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'pg_temp'
AS $function$
DECLARE
    v_now timestamptz;
    t tournament.tournament%ROWTYPE;
    u iam.user_account%ROWTYPE;
    v_active integer;
    v_occupied integer;
    v_banned boolean;
    v_boundary_restore boolean := false;
BEGIN
    v_now := tournament.require_command_scope(COALESCE(NEW.tournament_id, OLD.tournament_id), NULL, NULL, COALESCE(NEW.user_id, OLD.user_id));
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Tournament Participant history may not be deleted';
    END IF;

    SELECT * INTO t FROM tournament.tournament WHERE id = NEW.tournament_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Tournament does not exist'; END IF;
    SELECT * INTO u FROM iam.user_account WHERE id = NEW.user_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Tournament Participant User does not exist'; END IF;
    SELECT EXISTS (
        SELECT 1 FROM trust.ban_instance b
         WHERE b.user_id = NEW.user_id AND b.status = 'ACTIVE'
           AND b.activated_at <= v_now AND b.ends_at > v_now
    ) INTO v_banned;

    IF TG_OP='UPDATE' AND OLD.status='INELIGIBLE' AND OLD.ineligible_reason='BAN' AND NEW.status='JOINED' THEN
        IF t.status='WAITING_FOR_PLAYERS' AND v_now >= t.checkpoint_at
           AND NOT EXISTS (
               SELECT 1 FROM trust.ban_instance b
                WHERE b.user_id=NEW.user_id AND b.activated_at <= t.checkpoint_at AND b.ends_at > t.checkpoint_at
           ) THEN
            v_boundary_restore := true;
        ELSIF t.status='CONFIRMED' AND v_now >= t.start_at
           AND NOT EXISTS (
               SELECT 1 FROM trust.ban_instance b
                WHERE b.user_id=NEW.user_id AND b.activated_at <= t.start_at AND b.ends_at >= t.start_at  -- v1.1.27: a Ban ending exactly at startAt does not restore (Product §37.11)
           ) THEN
            v_boundary_restore := true;
        END IF;
    END IF;

    -- Once a due boundary is reached, no participant mutation may change its result.
    -- The only exception is catching up a BAN-only restoration that was already effective at that boundary.
    IF t.status='WAITING_FOR_PLAYERS' AND v_now >= t.checkpoint_at AND NOT v_boundary_restore THEN
        RAISE EXCEPTION 'Tournament checkpoint is overdue; resolve the checkpoint before participant mutation';
    END IF;
    IF t.status='CONFIRMED' AND v_now >= t.start_at AND NOT v_boundary_restore THEN
        RAISE EXCEPTION 'Tournament start is due; resolve startAt before participant mutation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        NEW.joined_at := v_now;
        NEW.status_changed_at := NEW.joined_at;
        NEW.status := 'JOINED';
        NEW.left_at := NULL;
        NEW.removed_at := NULL;
        NEW.ineligible_reason := NULL;

        IF NOT u.phone_verified OR u.anonymised_at IS NOT NULL OR v_banned THEN
            RAISE EXCEPTION 'Tournament join requires an Account-Verified, non-banned active User';
        END IF;
        IF t.status = 'PENDING_VENUE_APPROVAL' THEN
            IF t.host_type <> 'PLAYER' OR NOT t.creator_participates OR NEW.user_id IS DISTINCT FROM t.creator_user_id
               OR NOT tournament.created_in_command(t.id) THEN
                RAISE EXCEPTION 'While PENDING_VENUE_APPROVAL only the participating Player Creator may have a Participant row';
            END IF;
        ELSIF t.status NOT IN ('WAITING_FOR_PLAYERS','CONFIRMED') OR v_now >= t.start_at THEN
            RAISE EXCEPTION 'Users may join only WAITING_FOR_PLAYERS/CONFIRMED before startAt';
        END IF;
        v_occupied := tournament.capacity_occupied_count(t.id);
        IF v_occupied >= t.capacity THEN
            RAISE EXCEPTION 'Tournament participant capacity is full, including BAN-only reserved slots' USING CONSTRAINT = 'gd_tournament_capacity_full';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tournament_id IS DISTINCT FROM OLD.tournament_id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.joined_at IS DISTINCT FROM OLD.joined_at THEN
        RAISE EXCEPTION 'Tournament Participant identity/join timestamp is immutable';
    END IF;
    IF OLD.left_at IS NOT NULL AND NEW.left_at IS DISTINCT FROM OLD.left_at THEN
        RAISE EXCEPTION 'left_at is immutable once recorded';
    END IF;
    IF OLD.removed_at IS NOT NULL AND NEW.removed_at IS DISTINCT FROM OLD.removed_at THEN
        RAISE EXCEPTION 'removed_at is immutable once recorded';
    END IF;
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        -- v1.1.17 cause precedence: account deletion permanently defeats BAN-only
        -- restoration eligibility. Permit exactly one reason-only reclassification
        -- while retaining the existing INELIGIBLE status and its status timestamp.
        IF OLD.status='INELIGIBLE' AND OLD.ineligible_reason='BAN' AND u.anonymised_at IS NOT NULL
           AND t.status IN ('PENDING_VENUE_APPROVAL','WAITING_FOR_PLAYERS','CONFIRMED','ACTIVE') THEN
            IF NEW.status_changed_at IS DISTINCT FROM OLD.status_changed_at
               OR NEW.left_at IS DISTINCT FROM OLD.left_at
               OR NEW.removed_at IS DISTINCT FROM OLD.removed_at THEN
                RAISE EXCEPTION 'BAN to ACCOUNT_DELETED cause reclassification may not mutate other participant lifecycle facts';
            END IF;
            NEW.ineligible_reason := 'ACCOUNT_DELETED';
            RETURN NEW;
        END IF;
        IF NEW.status_changed_at IS DISTINCT FROM OLD.status_changed_at
           OR NEW.left_at IS DISTINCT FROM OLD.left_at OR NEW.removed_at IS DISTINCT FROM OLD.removed_at
           OR NEW.ineligible_reason IS DISTINCT FROM OLD.ineligible_reason THEN
            RAISE EXCEPTION 'Participant lifecycle facts change only with a status transition except BAN to ACCOUNT_DELETED cause precedence';
        END IF;
        RETURN NEW;
    END IF;
    IF t.status IN ('COMPLETED','REJECTED','CANCELLED') THEN
        RAISE EXCEPTION 'Participant state cannot change after Tournament terminal state';
    END IF;

    NEW.status_changed_at := v_now;
    IF NEW.status = 'JOINED' THEN
        IF NOT v_boundary_restore AND (v_now >= t.start_at OR NOT (
               t.status IN ('WAITING_FOR_PLAYERS','CONFIRMED')
               OR (t.status='PENDING_VENUE_APPROVAL' AND t.host_type='PLAYER' AND t.creator_participates AND NEW.user_id=t.creator_user_id)
           )) THEN
            RAISE EXCEPTION 'Participant may return/join only before startAt in an eligible Tournament lifecycle state';
        END IF;
        IF NOT u.phone_verified OR u.anonymised_at IS NOT NULL OR v_banned THEN
            RAISE EXCEPTION 'JOINED participant must be Account-Verified, active and not banned';
        END IF;
        NEW.ineligible_reason := NULL;
        v_occupied := tournament.capacity_occupied_count(t.id);
        -- BAN-only restoration consumes its already-reserved capacity slot, so it must
        -- not be rejected merely because all slots are occupied. Other returns still
        -- require a free slot.
        IF NOT (OLD.status='JOINED' OR (OLD.status='INELIGIBLE' AND OLD.ineligible_reason='BAN'))
           AND v_occupied >= t.capacity THEN
            RAISE EXCEPTION 'Tournament participant capacity is full, including BAN-only reserved slots' USING CONSTRAINT = 'gd_tournament_capacity_full';
        END IF;
    ELSIF NEW.status = 'LEFT' THEN
        IF v_now >= t.start_at THEN
            RAISE EXCEPTION 'Participant may Leave only before startAt';
        END IF;
        IF NEW.left_at IS NULL THEN NEW.left_at := NEW.status_changed_at; END IF;
    ELSIF NEW.status = 'REMOVED' THEN
        IF v_now >= t.start_at THEN
            RAISE EXCEPTION 'Organizer may Remove a Participant only before startAt';
        END IF;
        IF NEW.removed_at IS NULL THEN NEW.removed_at := NEW.status_changed_at; END IF;
    ELSIF NEW.status = 'INELIGIBLE' THEN
        -- ACCOUNT_DELETED has precedence over BAN because a deleted/anonymised
        -- account can never become JOINED again when a Ban later expires/lifts.
        IF u.anonymised_at IS NOT NULL THEN
            NEW.ineligible_reason := 'ACCOUNT_DELETED';
        ELSIF v_banned THEN
            NEW.ineligible_reason := 'BAN';
        ELSE
            RAISE EXCEPTION 'INELIGIBLE is valid only for BAN or ACCOUNT_DELETED';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

-- ---------- iam.guard_user_account_update (Platform lane; final of 1 definition(s) in V1) ----------
-- [D] diagnostic: gd_username_cooldown
CREATE OR REPLACE FUNCTION iam.guard_user_account_update()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_last timestamptz;
BEGIN
 IF NEW.id IS DISTINCT FROM OLD.id THEN RAISE EXCEPTION 'User ID is immutable'; END IF;
 IF OLD.anonymised_at IS NOT NULL THEN
   IF to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD) THEN
     RAISE EXCEPTION 'An anonymised (deleted) account is immutable';
   END IF;
   RETURN NEW;
 END IF;
 IF NEW.anonymised_at IS NOT NULL THEN
   -- PCR-002 / O19: only the audited anonymisation producer may complete deletion.
   IF NOT technical.has_write('iam.user_account',NEW.id::text) THEN
     RAISE EXCEPTION 'Account anonymisation requires iam.anonymise_user (O19)';
   END IF;
   IF NOT EXISTS(SELECT 1 FROM iam.account_anonymisation a WHERE a.user_id=NEW.id AND a.performed_at=NEW.anonymised_at) THEN
     RAISE EXCEPTION 'Account anonymisation requires its account_anonymisation record';
   END IF;
   RETURN NEW;
 END IF;
 -- Product §7.5: Username editable, unique, 30-day change cooldown.
 IF NEW.username IS DISTINCT FROM OLD.username THEN
   PERFORM 1 FROM iam.user_commitment_guard g WHERE g.user_id=NEW.id FOR UPDATE;
   SELECT max(c.changed_at) INTO v_last FROM iam.username_change c WHERE c.user_id=NEW.id;
   IF v_last IS NOT NULL AND now() < v_last + interval '30 days' THEN
     RAISE EXCEPTION 'Username change cooldown is active until %',v_last + interval '30 days' USING CONSTRAINT = 'gd_username_cooldown';
   END IF;
 END IF;
 RETURN NEW;
END;
$function$;

-- ---------- iam.guard_otp_challenge (Platform lane; final of 1 definition(s) in V1) ----------
-- [D] diagnostic: gd_otp_hourly_limit, gd_otp_resend_too_soon
CREATE OR REPLACE FUNCTION iam.guard_otp_challenge()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_count integer;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'OTP challenges are retained for rate limiting and audit'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.issued_at>clock_timestamp() THEN RAISE EXCEPTION 'OTP issued_at cannot be in the future'; END IF;
   IF NEW.attempts<>0 OR NEW.consumed_at IS NOT NULL OR NEW.superseded_at IS NOT NULL THEN
     RAISE EXCEPTION 'A new OTP starts unused';
   END IF;
   PERFORM pg_advisory_xact_lock(hashtextextended('e7gz.otp:'||NEW.destination,0));
   IF EXISTS(SELECT 1 FROM iam.otp_challenge o WHERE o.destination=NEW.destination AND o.purpose=NEW.purpose
             AND o.issued_at > NEW.issued_at - interval '60 seconds') THEN
     RAISE EXCEPTION 'OTP resend is available 60 seconds after the previous send' USING CONSTRAINT = 'gd_otp_resend_too_soon';
   END IF;
   SELECT count(*) INTO v_count FROM iam.otp_challenge o
    WHERE o.destination=NEW.destination AND o.issued_at > NEW.issued_at - interval '1 hour';
   IF v_count>=5 THEN RAISE EXCEPTION 'Maximum 5 OTP sends per hour per destination' USING CONSTRAINT = 'gd_otp_hourly_limit'; END IF;
   -- A new OTP invalidates the previous OTP.
   UPDATE iam.otp_challenge SET superseded_at=NEW.issued_at
    WHERE destination=NEW.destination AND purpose=NEW.purpose AND consumed_at IS NULL AND superseded_at IS NULL;
   RETURN NEW;
 END IF;
 IF NEW.id<>OLD.id OR NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.purpose<>OLD.purpose OR NEW.destination<>OLD.destination
    OR NEW.code_hash<>OLD.code_hash OR NEW.expires_at<>OLD.expires_at OR NEW.issued_at<>OLD.issued_at THEN
   RAISE EXCEPTION 'OTP challenge identity, code and validity are immutable';
 END IF;
 IF (OLD.consumed_at IS NOT NULL AND NEW.consumed_at IS DISTINCT FROM OLD.consumed_at)
    OR (OLD.superseded_at IS NOT NULL AND NEW.superseded_at IS DISTINCT FROM OLD.superseded_at) THEN
   RAISE EXCEPTION 'OTP consumption/supersession is recorded once';
 END IF;
 IF NEW.attempts<OLD.attempts OR NEW.attempts>OLD.attempts+1 THEN RAISE EXCEPTION 'OTP attempts increase by one per verification'; END IF;
 IF (NEW.attempts>OLD.attempts OR (OLD.consumed_at IS NULL AND NEW.consumed_at IS NOT NULL))
    AND (OLD.consumed_at IS NOT NULL OR OLD.superseded_at IS NOT NULL OR now()>=OLD.expires_at OR OLD.attempts>=5) THEN
   RAISE EXCEPTION 'OTP is no longer usable (expired, superseded, consumed or out of attempts)';
 END IF;
 RETURN NEW;
END;
$function$;

-- ---------- geo.guard_area_deactivation (Platform lane; final of 1 definition(s) in V1) ----------
-- [D] diagnostic: gd_area_has_active_venues
CREATE OR REPLACE FUNCTION geo.guard_area_deactivation()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Areas are deactivated, never deleted; historical references remain'; END IF;
 IF OLD.is_active AND NOT NEW.is_active AND EXISTS(
    SELECT 1 FROM venue.venue v WHERE v.area_id=NEW.id AND v.marketplace_status IN ('ACTIVE','SUSPENDED')) THEN
   RAISE EXCEPTION 'Area cannot be deactivated while a Venue in it is ACTIVE or SUSPENDED' USING CONSTRAINT = 'gd_area_has_active_venues';
 END IF;
 RETURN NEW;
END;
$function$;

-- ---------- venue.guard_venue_area (Platform lane; final of 1 definition(s) in V1) ----------
-- [D] diagnostic: gd_venue_area_inactive_assignment, gd_venue_area_inactive_status
CREATE OR REPLACE FUNCTION venue.guard_venue_area()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_active boolean;
BEGIN
 SELECT a.is_active INTO v_active FROM geo.area a WHERE a.id=NEW.area_id FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Venue Area does not exist'; END IF;
 IF NOT v_active AND (TG_OP='INSERT' OR NEW.area_id IS DISTINCT FROM OLD.area_id) THEN
   RAISE EXCEPTION 'Only an ACTIVE Area is available for new Venue assignment' USING CONSTRAINT = 'gd_venue_area_inactive_assignment';
 END IF;
 IF NOT v_active AND NEW.marketplace_status IN ('ACTIVE','SUSPENDED')
    AND (TG_OP='INSERT' OR NEW.marketplace_status IS DISTINCT FROM OLD.marketplace_status OR NEW.area_id IS DISTINCT FROM OLD.area_id) THEN
   RAISE EXCEPTION 'A Venue in an inactive Area cannot be ACTIVE or SUSPENDED' USING CONSTRAINT = 'gd_venue_area_inactive_status';
 END IF;
 RETURN NEW;
END;
$function$;

-- ---------- operations.guard_maintenance_window (Platform lane; final of 1 definition(s) in V1) ----------
-- [D] diagnostic: gd_maintenance_overlaps_commitment, gd_maintenance_owner_password_required
CREATE OR REPLACE FUNCTION operations.guard_maintenance_window()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_max_minutes numeric;
    v_scope varchar(80);
    v_emergency_resource uuid;
    v_conflict boolean;
    v_venue_id uuid;
BEGIN
    -- Resource row is the serialization point for Maintenance versus commitments.
    SELECT r.venue_id INTO v_venue_id
      FROM resource.resource r
     WHERE r.id = NEW.resource_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Resource % does not exist', NEW.resource_id;
    END IF;

    IF TG_OP = 'UPDATE' AND (
       NEW.resource_id IS DISTINCT FROM OLD.resource_id OR
       NEW.created_from_emergency_id IS DISTINCT FROM OLD.created_from_emergency_id) THEN
        RAISE EXCEPTION 'Maintenance Resource/source identity is immutable';
    END IF;

    IF NEW.created_from_emergency_id IS NOT NULL THEN
        SELECT e.scope, e.resource_id
          INTO v_scope, v_emergency_resource
          FROM operations.emergency e
         WHERE e.id = NEW.created_from_emergency_id;
        IF NOT FOUND OR v_scope <> 'RESOURCE' OR v_emergency_resource IS DISTINCT FROM NEW.resource_id THEN
            RAISE EXCEPTION 'Emergency-generated Maintenance must reference a RESOURCE Emergency for the same Resource';
        END IF;
    END IF;

    -- Revalidate conflicts/threshold only when a new interval is created or the interval changes.
    -- A later Venue Closure is allowed to overlap pre-existing Maintenance, so a reason-only edit
    -- must not be rejected merely because that Closure now exists.
    IF TG_OP = 'INSERT' OR NEW.period IS DISTINCT FROM OLD.period THEN
        -- Authoritative conflict check even when a technical MAINTENANCE reservation row is not materialized.
        SELECT EXISTS (
            SELECT 1
              FROM inventory.resource_reservation rr
             WHERE rr.resource_id = NEW.resource_id
               AND rr.active_flag
               AND rr.reservation_type <> 'MAINTENANCE'
               AND rr.planned_range && NEW.period
        ) INTO v_conflict;
        IF v_conflict THEN
            RAISE EXCEPTION 'Maintenance may not overlap an active Booking/Tournament/Closure commitment' USING CONSTRAINT = 'gd_maintenance_overlaps_commitment';
        END IF;

        SELECT EXISTS (
            SELECT 1 FROM operations.venue_closure c
            WHERE c.venue_id = v_venue_id AND c.period && NEW.period
        ) INTO v_conflict;
        IF v_conflict THEN
            RAISE EXCEPTION 'New/extended Maintenance may not overlap an existing Venue Closure';
        END IF;

        v_max_minutes := operations.maintenance_candidate_max_rolling_minutes(
            NEW.resource_id,
            NEW.period,
            CASE WHEN TG_OP = 'UPDATE' THEN OLD.id ELSE NULL END
        );

        -- Genuine emergency declaration remains unprotected. Emergency-generated Maintenance is counted,
        -- but the emergency itself is not blocked by Owner Password. A manual/non-emergency Maintenance
        -- that pushes any rolling 48h total above 24h is protected.
        IF TG_OP = 'INSERT' AND NEW.created_from_emergency_id IS NULL
           AND v_max_minutes > 1440 AND NEW.owner_password_used = false THEN
            RAISE EXCEPTION 'Owner Password is required: Maintenance exceeds 24 hours in a rolling 48-hour window' USING CONSTRAINT = 'gd_maintenance_owner_password_required';
        END IF;

        -- Any edit that extends the resulting rolling total beyond the threshold is protected.
        IF TG_OP = 'UPDATE' AND NEW.period IS DISTINCT FROM OLD.period
           AND v_max_minutes > 1440 AND NEW.owner_password_used = false THEN
            RAISE EXCEPTION 'Owner Password is required for a Maintenance edit exceeding the rolling threshold' USING CONSTRAINT = 'gd_maintenance_owner_password_required';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

-- ---------- review.create_review (Platform lane; final of 1 definition(s) in V1) ----------
-- [B] branch split, [D] diagnostic: gd_review_already_exists, gd_review_author_deleted, gd_review_author_unverified, gd_review_cooldown
CREATE OR REPLACE FUNCTION review.create_review(p_user uuid,p_venue uuid,p_rating smallint,p_comment text,p_correlation uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_now timestamptz; u iam.user_account%ROWTYPE; v_status text; v_id uuid:=gen_random_uuid(); tok uuid; c review.review_cooldown%ROWTYPE;
BEGIN
 IF p_correlation IS NULL THEN RAISE EXCEPTION 'Correlation is required'; END IF;
 v_now:=review.customer_command(p_user,p_venue);
 SELECT * INTO u FROM iam.user_account WHERE id=p_user;
 IF NOT FOUND THEN
   RAISE EXCEPTION 'Only a phone-verified, non-anonymised User can create a Review';
 ELSIF u.anonymised_at IS NOT NULL THEN
   RAISE EXCEPTION 'Only a phone-verified, non-anonymised User can create a Review' USING CONSTRAINT = 'gd_review_author_deleted';
 ELSIF NOT u.phone_verified THEN
   RAISE EXCEPTION 'Only a phone-verified, non-anonymised User can create a Review' USING CONSTRAINT = 'gd_review_author_unverified';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM venue.venue v WHERE v.id=p_venue) THEN RAISE EXCEPTION 'Unknown Venue'; END IF;
 IF EXISTS(SELECT 1 FROM review.review r WHERE r.user_id=p_user AND r.venue_id=p_venue AND r.status<>'REMOVED') THEN
   RAISE EXCEPTION 'User already has a current Review for this Venue; edit it instead' USING CONSTRAINT = 'gd_review_already_exists';
 END IF;
 SELECT * INTO c FROM review.review_cooldown x WHERE x.user_id=p_user AND x.venue_id=p_venue ORDER BY x.cooldown_until DESC LIMIT 1;
 IF FOUND AND v_now<c.cooldown_until THEN
   RAISE EXCEPTION 'Review cooldown after Admin removal is active until %',c.cooldown_until USING CONSTRAINT = 'gd_review_cooldown';
 END IF;
 v_status:=CASE WHEN FOUND THEN 'PENDING_MODERATION' ELSE 'ACTIVE' END;
 tok:=technical.enter_write('review.review',v_id::text);
 INSERT INTO review.review(id,user_id,venue_id,rating,comment,status,created_at,updated_at)
 VALUES(v_id,p_user,p_venue,p_rating,p_comment,v_status,v_now,v_now);
 PERFORM technical.leave_write(tok);
 PERFORM review.write_audit('CUSTOMER',p_user,'REVIEW_CREATED',v_id,NULL,p_correlation,v_now,NULL,v_status,p_user,p_venue,
   jsonb_build_object('rating',p_rating));
 PERFORM review.refresh_venue_rating(p_venue,v_now);
 RETURN v_id;
END;
$function$;

-- ---------- review.report_review (Platform lane; final of 2 definition(s) in V1) ----------
-- [D] diagnostic: gd_review_report_open
CREATE OR REPLACE FUNCTION review.report_review(p_review uuid, p_reporter uuid, p_reason text, p_details text, p_correlation uuid)
 RETURNS uuid
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'pg_catalog', 'pg_temp'
AS $function$
DECLARE r review.review%ROWTYPE; v_now timestamptz; v_id uuid:=gen_random_uuid(); tok uuid; aid uuid:=gen_random_uuid();
BEGIN
 IF p_reporter IS NULL OR p_correlation IS NULL OR NULLIF(btrim(p_reason),'') IS NULL THEN RAISE EXCEPTION 'Reporter, correlation and reason are required'; END IF;
 SELECT * INTO r FROM review.review WHERE id=p_review;
 IF NOT FOUND OR r.status<>'ACTIVE' OR r.author_deleted_at IS NOT NULL THEN RAISE EXCEPTION 'Only an ACTIVE public Review can be reported'; END IF;
 v_now:=review.customer_command(p_reporter,r.venue_id);
 -- v1.1.27: a Review of a deleted/anonymised account is no longer public (PCR-002).
 IF EXISTS(SELECT 1 FROM iam.user_account ua WHERE ua.id=r.user_id AND ua.anonymised_at IS NOT NULL) THEN
   RAISE EXCEPTION 'Only an ACTIVE public Review can be reported';
 END IF;
 -- v1.1.27: one unresolved report per reporter per Review (duplicate reports rejected).
 PERFORM pg_advisory_xact_lock(hashtextextended('e7gz.review_report:'||p_review::text||':'||p_reporter::text,0));
 IF EXISTS(SELECT 1 FROM review.review_report x WHERE x.review_id=p_review AND x.reporter_user_id=p_reporter AND x.resolved_at IS NULL) THEN
   RAISE EXCEPTION 'This User already has an unresolved report for this Review' USING CONSTRAINT = 'gd_review_report_open';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM iam.user_account u WHERE u.id=p_reporter AND u.anonymised_at IS NULL) THEN RAISE EXCEPTION 'Signed-in active Customer required'; END IF;
 tok:=technical.enter_write('review.review_report',v_id::text);
 INSERT INTO review.review_report(id,review_id,reporter_user_id,reason,details,created_at,correlation_id)
 VALUES(v_id,p_review,p_reporter,btrim(p_reason),NULLIF(btrim(p_details),''),v_now,p_correlation);
 PERFORM technical.leave_write(tok);
 INSERT INTO audit.audit_event(id,occurred_at,actor_type,actor_id,action,subject_type,subject_id,reason,correlation_id,payload,context_user_id,context_venue_id)
 VALUES(aid,v_now,'CUSTOMER',p_reporter,'REVIEW_REPORTED','REVIEW',p_review,btrim(p_reason),p_correlation,jsonb_build_object('reviewReportId',v_id),p_reporter,r.venue_id);
 RETURN v_id;
END;
$function$;

-- ---------- technical.idempotency_begin (Platform lane; final of 1 definition(s) in V1) ----------
-- [D] diagnostic: gd_idempotency_key_reused
CREATE OR REPLACE FUNCTION technical.idempotency_begin(p_principal_type text,p_principal_id uuid,p_endpoint text,p_key text,
  p_fingerprint text,p_expires_at timestamptz,p_correlation uuid)
RETURNS TABLE(outcome text,response_status smallint,response_body jsonb,result_aggregate_type text,result_aggregate_id uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_now timestamptz:=clock_timestamp(); ex technical.idempotency_record%ROWTYPE; tok uuid; n integer; rk text;
BEGIN
 IF p_principal_type IN ('ADMIN','CUSTOMER','VENUE') THEN PERFORM technical.assert_principal(p_principal_type,p_principal_id,true);
 ELSIF p_principal_type<>'SYSTEM' OR p_principal_id IS NULL THEN RAISE EXCEPTION 'Invalid idempotency principal';
 END IF;
 IF p_expires_at IS NULL OR p_expires_at<=v_now OR p_correlation IS NULL THEN RAISE EXCEPTION 'Idempotency requires future expiry and correlation'; END IF;
 rk:=technical.idempotency_row_key(p_principal_type,p_principal_id,p_endpoint,p_key);
 tok:=technical.enter_write('technical.idempotency_record',rk);
 INSERT INTO technical.idempotency_record(principal_type,principal_id,endpoint,idempotency_key,request_fingerprint,state,correlation_id,created_at,expires_at)
 VALUES(p_principal_type,p_principal_id,p_endpoint,p_key,p_fingerprint,'IN_PROGRESS',p_correlation,v_now,p_expires_at)
 ON CONFLICT ON CONSTRAINT idempotency_record_pk DO NOTHING;
 GET DIAGNOSTICS n=ROW_COUNT;
 IF n=1 THEN PERFORM technical.leave_write(tok); RETURN QUERY SELECT 'NEW'::text,NULL::smallint,NULL::jsonb,NULL::text,NULL::uuid; RETURN; END IF;
 SELECT * INTO STRICT ex FROM technical.idempotency_record x WHERE x.principal_type=p_principal_type AND x.principal_id=p_principal_id
   AND x.endpoint=p_endpoint AND x.idempotency_key=p_key FOR UPDATE;
 IF ex.expires_at<=v_now THEN
   DELETE FROM technical.idempotency_record x WHERE x.principal_type=p_principal_type AND x.principal_id=p_principal_id
     AND x.endpoint=p_endpoint AND x.idempotency_key=p_key;
   INSERT INTO technical.idempotency_record(principal_type,principal_id,endpoint,idempotency_key,request_fingerprint,state,correlation_id,created_at,expires_at)
   VALUES(p_principal_type,p_principal_id,p_endpoint,p_key,p_fingerprint,'IN_PROGRESS',p_correlation,v_now,p_expires_at);
   PERFORM technical.leave_write(tok);
   RETURN QUERY SELECT 'NEW'::text,NULL::smallint,NULL::jsonb,NULL::text,NULL::uuid; RETURN;
 END IF;
 PERFORM technical.leave_write(tok);
 IF ex.request_fingerprint<>p_fingerprint THEN RAISE EXCEPTION 'Idempotency key reused with a different request' USING CONSTRAINT = 'gd_idempotency_key_reused'; END IF;
 IF ex.state='COMPLETED' THEN
   RETURN QUERY SELECT 'REPLAY'::text,ex.response_status,ex.response_body,ex.result_aggregate_type,ex.result_aggregate_id; RETURN;
 END IF;
 RAISE EXCEPTION 'Idempotent request is already in progress in this transaction';
END;
$function$;
