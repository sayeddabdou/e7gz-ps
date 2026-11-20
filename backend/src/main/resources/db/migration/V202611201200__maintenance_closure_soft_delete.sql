-- =====================================================================================================
-- E7-81 / physical model v1.1.44 — deleting a Maintenance window or a normal Venue Closure (FR-OPS-01, FR-VEN-30;
-- Sprint 5 contract E7-77: DELETE /venue/maintenance/{id} and DELETE /venue/closures/{id}). For Product Owner review.
--
-- Neither table has a delete: the runtime roles have no DELETE grant outside the security baseline's short list, and
-- history matters (FR-OPS-01: create, edit and delete are audited; a Maintenance window counts toward the rolling
-- 48-hour total while it exists, FR-OPS-03). A delete is therefore a soft delete: the row stays, with deleted_at set.
--
--   1. operations.maintenance_window.deleted_at and operations.venue_closure.deleted_at: NULL while the row counts.
--   2. operations.guard_operational_soft_delete + tr_00_*_soft_delete: a row is never inserted deleted; deleting sets
--      deleted_at to the command instant (tournament.command_now()) and changes nothing else; a deleted row never
--      changes again (no undelete).
--   3. Every reader of the two tables skips a deleted row (their V1 definitions - V202609291200's for
--      guard_maintenance_window - plus only the added lines): the rolling-threshold union (O3), the new-Maintenance
--      Closure check, the Closure affected-set assertion (O4), the derived-row checks (O9), owner-review resolution,
--      emergency conversion and the Tournament operational check.
--   4. operations.assert_operational_guard_rows_match_owner: a deleted owner keeps no active MAINTENANCE / CLOSURE
--      guard row - the delete releases them in the same transaction. Its two deferred triggers now also fire on
--      UPDATE OF deleted_at.
--
-- Existing rows keep deleted_at NULL: nothing is deleted by this migration and no backfill is needed. Grants: the
-- security baseline already grants SELECT/INSERT/UPDATE on every operations table, and EXECUTE on the new function.
-- =====================================================================================================

ALTER TABLE operations.maintenance_window ADD COLUMN deleted_at timestamp with time zone;
COMMENT ON COLUMN operations.maintenance_window.deleted_at IS E'Soft delete (v1.1.44, E7-81): NULL while the window counts; set once to the deleting command instant. A deleted window blocks nothing and no longer counts toward the rolling 48-hour total.';
ALTER TABLE operations.venue_closure ADD COLUMN deleted_at timestamp with time zone;
COMMENT ON COLUMN operations.venue_closure.deleted_at IS E'Soft delete (v1.1.44, E7-81): NULL while the Closure blocks; set once to the deleting command instant. Bookings it cancelled stay cancelled.';

-- A row is inserted live; the delete sets deleted_at to the command instant and nothing else; a deleted row is final.
CREATE OR REPLACE FUNCTION operations.guard_operational_soft_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.deleted_at IS NOT NULL THEN
            RAISE EXCEPTION 'A new %.% row cannot be inserted deleted', TG_TABLE_SCHEMA, TG_TABLE_NAME;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.deleted_at IS NOT NULL THEN
        RAISE EXCEPTION 'A deleted %.% row never changes', TG_TABLE_SCHEMA, TG_TABLE_NAME;
    END IF;
    IF NEW.deleted_at IS NOT NULL THEN
        IF NEW.deleted_at IS DISTINCT FROM tournament.command_now() THEN
            RAISE EXCEPTION 'A %.% row is deleted at the command instant', TG_TABLE_SCHEMA, TG_TABLE_NAME;
        END IF;
        IF (to_jsonb(NEW) - 'deleted_at') IS DISTINCT FROM (to_jsonb(OLD) - 'deleted_at') THEN
            RAISE EXCEPTION 'Deleting a %.% row changes nothing else', TG_TABLE_SCHEMA, TG_TABLE_NAME;
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER tr_00_maintenance_soft_delete
BEFORE INSERT OR UPDATE ON operations.maintenance_window
FOR EACH ROW EXECUTE FUNCTION operations.guard_operational_soft_delete();

CREATE TRIGGER tr_00_venue_closure_soft_delete
BEFORE INSERT OR UPDATE ON operations.venue_closure
FOR EACH ROW EXECUTE FUNCTION operations.guard_operational_soft_delete();

CREATE OR REPLACE FUNCTION operations.maintenance_union_minutes_in_window(
    p_resource_id uuid,
    p_window_start timestamptz,
    p_window_end timestamptz,
    p_candidate tstzrange DEFAULT NULL,
    p_exclude_id uuid DEFAULT NULL
)
RETURNS numeric
LANGUAGE sql
STABLE
AS $function$
WITH raw_segments AS (
    SELECT GREATEST(lower(m.period), p_window_start) AS s,
           LEAST(upper(m.period), p_window_end) AS e
      FROM operations.maintenance_window m
     WHERE m.resource_id = p_resource_id
       AND m.deleted_at IS NULL
       AND (p_exclude_id IS NULL OR m.id <> p_exclude_id)
       AND m.period && tstzrange(p_window_start, p_window_end, '[)')
    UNION ALL
    SELECT GREATEST(lower(p_candidate), p_window_start),
           LEAST(upper(p_candidate), p_window_end)
     WHERE p_candidate IS NOT NULL
       AND p_candidate && tstzrange(p_window_start, p_window_end, '[)')
), segments AS (
    SELECT s, e FROM raw_segments WHERE s < e
), ordered AS (
    SELECT s, e,
           max(e) OVER (ORDER BY s, e ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS previous_max_end
      FROM segments
), marked AS (
    SELECT s, e,
           CASE WHEN previous_max_end IS NULL OR s > previous_max_end THEN 1 ELSE 0 END AS island_start
      FROM ordered
), grouped AS (
    SELECT s, e,
           sum(island_start) OVER (ORDER BY s, e ROWS UNBOUNDED PRECEDING) AS island_id
      FROM marked
), merged AS (
    SELECT min(s) AS s, max(e) AS e FROM grouped GROUP BY island_id
)
SELECT COALESCE(sum(EXTRACT(epoch FROM (e - s)) / 60.0), 0)::numeric
  FROM merged;
$function$;

CREATE OR REPLACE FUNCTION operations.maintenance_candidate_max_rolling_minutes(
    p_resource_id uuid,
    p_candidate tstzrange,
    p_exclude_id uuid DEFAULT NULL
)
RETURNS numeric
LANGUAGE sql
STABLE
AS $function$
WITH relevant AS (
    SELECT m.period
      FROM operations.maintenance_window m
     WHERE m.resource_id = p_resource_id
       AND m.deleted_at IS NULL
       AND (p_exclude_id IS NULL OR m.id <> p_exclude_id)
       AND m.period && tstzrange(lower(p_candidate) - interval '48 hours',
                                 upper(p_candidate) + interval '48 hours', '[)')
    UNION ALL
    SELECT p_candidate
), points AS (
    SELECT lower(period) AS p FROM relevant
    UNION
    SELECT upper(period) - interval '48 hours' FROM relevant
), windows AS (
    SELECT p AS window_start, p + interval '48 hours' AS window_end
      FROM points
     WHERE p < upper(p_candidate)
       AND p + interval '48 hours' > lower(p_candidate)
), totals AS (
    SELECT operations.maintenance_union_minutes_in_window(
               p_resource_id, window_start, window_end, p_candidate, p_exclude_id
           ) AS minutes
      FROM windows
)
SELECT COALESCE(max(minutes), 0)::numeric FROM totals;
$function$;

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
              AND c.deleted_at IS NULL
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

CREATE OR REPLACE FUNCTION operations.assert_venue_closure_affected_set_resolved()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_venue uuid;
    v_period tstzrange;
BEGIN
    -- v1.1.44 (E7-81): a deleted Closure blocks nothing, so it has no affected set to resolve.
    IF EXISTS (SELECT 1 FROM operations.venue_closure c WHERE c.id = NEW.id AND c.deleted_at IS NOT NULL) THEN
        RETURN NULL;
    END IF;
    SELECT c.venue_id, c.period INTO v_venue, v_period
      FROM operations.venue_closure c WHERE c.id = NEW.id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF EXISTS (
        SELECT 1 FROM booking.booking b
        WHERE b.venue_id = v_venue
          AND b.status IN ('PENDING','AWAITING_CUSTOMER_CONFIRMATION','CONFIRMED','ACTIVE')
          AND tstzrange(b.start_at,b.end_at,'[)') && v_period
    ) THEN
        RAISE EXCEPTION 'Venue Closure cannot commit with unresolved Booking/Manual commitments in its interval';
    END IF;

    IF EXISTS (
        SELECT 1 FROM tournament.tournament t
        WHERE t.venue_id = v_venue
          AND t.status IN ('PENDING_VENUE_APPROVAL','WAITING_FOR_PLAYERS','CONFIRMED','ACTIVE')
          AND tstzrange(t.start_at,t.end_at,'[)') && v_period
    ) THEN
        RAISE EXCEPTION 'Venue Closure cannot commit while a conflicting Tournament remains unresolved';
    END IF;

    RETURN NULL;
END;
$function$;

CREATE OR REPLACE FUNCTION inventory.validate_operational_reservation_owner()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_resource uuid;
    v_period tstzrange;
    v_venue uuid;
BEGIN
    IF NOT NEW.active_flag OR NEW.reservation_type NOT IN ('MAINTENANCE','CLOSURE') THEN
        RETURN NEW;
    END IF;

    IF NEW.reservation_type = 'MAINTENANCE' THEN
        -- v1.1.44 (E7-81): a deleted Maintenance window holds no active guard row.
        IF EXISTS (SELECT 1 FROM operations.maintenance_window m WHERE m.id = NEW.maintenance_id AND m.deleted_at IS NOT NULL) THEN
            RAISE EXCEPTION 'A deleted Maintenance window cannot hold an active MAINTENANCE reservation';
        END IF;
        SELECT m.resource_id, m.period INTO v_resource, v_period
          FROM operations.maintenance_window m WHERE m.id = NEW.maintenance_id;
        IF NOT FOUND OR v_resource IS DISTINCT FROM NEW.resource_id
           OR lower(v_period) IS DISTINCT FROM NEW.planned_start
           OR upper(v_period) IS DISTINCT FROM NEW.planned_end THEN
            RAISE EXCEPTION 'Active MAINTENANCE reservation must exactly match its Maintenance owner';
        END IF;
    ELSE
        -- v1.1.44 (E7-81): a deleted Closure holds no active guard row.
        IF EXISTS (SELECT 1 FROM operations.venue_closure c WHERE c.id = NEW.closure_id AND c.deleted_at IS NOT NULL) THEN
            RAISE EXCEPTION 'A deleted Closure cannot hold an active CLOSURE reservation';
        END IF;
        SELECT c.venue_id, c.period INTO v_venue, v_period
          FROM operations.venue_closure c WHERE c.id = NEW.closure_id;
        SELECT r.venue_id INTO v_resource FROM resource.resource r WHERE r.id = NEW.resource_id;
        IF NOT FOUND OR v_resource IS DISTINCT FROM v_venue
           OR lower(v_period) IS DISTINCT FROM NEW.planned_start
           OR upper(v_period) IS DISTINCT FROM NEW.planned_end THEN
            RAISE EXCEPTION 'Active CLOSURE guard reservation must match the Closure interval and a Resource in that Venue';
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;

CREATE OR REPLACE FUNCTION operations.assert_operational_guard_rows_match_owner()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    IF TG_TABLE_NAME = 'maintenance_window' THEN
        -- v1.1.44 (E7-81): the delete released the window's guard row in the same transaction.
        IF NEW.deleted_at IS NOT NULL AND EXISTS (
            SELECT 1 FROM inventory.resource_reservation rr
            WHERE rr.active_flag AND rr.reservation_type = 'MAINTENANCE' AND rr.maintenance_id = NEW.id
        ) THEN
            RAISE EXCEPTION 'A deleted Maintenance window still holds an active MAINTENANCE guard row';
        END IF;
        IF EXISTS (
            SELECT 1 FROM inventory.resource_reservation rr
            WHERE rr.active_flag
              AND rr.reservation_type = 'MAINTENANCE'
              AND rr.maintenance_id = NEW.id
              AND (rr.resource_id IS DISTINCT FROM NEW.resource_id
                   OR rr.planned_start IS DISTINCT FROM lower(NEW.period)
                   OR rr.planned_end IS DISTINCT FROM upper(NEW.period))
        ) THEN
            RAISE EXCEPTION 'Active MAINTENANCE guard row is stale; release it and insert the guard for the edited interval in the same transaction';
        END IF;
    ELSE
        -- v1.1.44 (E7-81): the delete released the Closure's guard rows in the same transaction.
        IF NEW.deleted_at IS NOT NULL AND EXISTS (
            SELECT 1 FROM inventory.resource_reservation rr
            WHERE rr.active_flag AND rr.reservation_type = 'CLOSURE' AND rr.closure_id = NEW.id
        ) THEN
            RAISE EXCEPTION 'A deleted Closure still holds an active CLOSURE guard row';
        END IF;
        IF EXISTS (
            SELECT 1
              FROM inventory.resource_reservation rr
              JOIN resource.resource r ON r.id = rr.resource_id
             WHERE rr.active_flag
               AND rr.reservation_type = 'CLOSURE'
               AND rr.closure_id = NEW.id
               AND (r.venue_id IS DISTINCT FROM NEW.venue_id
                    OR rr.planned_start IS DISTINCT FROM lower(NEW.period)
                    OR rr.planned_end IS DISTINCT FROM upper(NEW.period))
        ) THEN
            RAISE EXCEPTION 'Active CLOSURE guard row is stale; replace it for the edited Closure interval in the same transaction';
        END IF;
    END IF;
    RETURN NULL;
END;
$function$;

DROP TRIGGER tr_maintenance_guard_rows_match_owner ON operations.maintenance_window;
CREATE CONSTRAINT TRIGGER tr_maintenance_guard_rows_match_owner
AFTER INSERT OR UPDATE OF period, deleted_at ON operations.maintenance_window
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION operations.assert_operational_guard_rows_match_owner();

DROP TRIGGER tr_closure_guard_rows_match_owner ON operations.venue_closure;
CREATE CONSTRAINT TRIGGER tr_closure_guard_rows_match_owner
AFTER INSERT OR UPDATE OF period, deleted_at ON operations.venue_closure
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION operations.assert_operational_guard_rows_match_owner();

CREATE OR REPLACE FUNCTION operations.assert_owner_review_resolution()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_base varchar(80);
    v_restriction varchar(80);
    v_blocked boolean;
    v_maintenance boolean;
BEGIN
    -- Query final state at deferred execution time rather than trusting the row image.
    SELECT r.base_state, r.operational_restriction
      INTO v_base, v_restriction
      FROM resource.resource r
     WHERE r.id = NEW.resource_id;

    IF NEW.resolved_at IS NULL THEN
        RETURN NULL;
    END IF;

    IF v_restriction <> 'NONE' THEN
        RAISE EXCEPTION 'resolved owner review requires operational_restriction=NONE for resource %', NEW.resource_id;
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM resource.resource_selling_block b
        WHERE b.resource_id = NEW.resource_id AND b.active
    ) INTO v_blocked;

    IF NEW.resolution = 'RETURN_TO_SERVICE' THEN
        IF v_base <> 'ACTIVE' OR v_blocked THEN
            RAISE EXCEPTION 'RETURN_TO_SERVICE requires ACTIVE base state and no active resource selling block';
        END IF;
        SELECT EXISTS (
            SELECT 1 FROM operations.maintenance_window m
            WHERE m.resource_id = NEW.resource_id AND m.period @> NEW.resolved_at
              AND m.deleted_at IS NULL
        ) INTO v_maintenance;
        IF v_maintenance THEN
            RAISE EXCEPTION 'RETURN_TO_SERVICE is blocked by Maintenance active at resolution time';
        END IF;
    ELSIF NEW.resolution = 'DISABLE' THEN
        IF v_base <> 'DISABLED' THEN
            RAISE EXCEPTION 'DISABLE owner-review resolution requires resource base_state=DISABLED';
        END IF;
    ELSIF NEW.resolution = 'ARCHIVE' THEN
        IF v_base <> 'ARCHIVED' THEN
            RAISE EXCEPTION 'ARCHIVE owner-review resolution requires resource base_state=ARCHIVED';
        END IF;
    ELSIF NEW.resolution = 'EXTEND_MAINTENANCE' THEN
        SELECT EXISTS (
            SELECT 1 FROM operations.maintenance_window m
            WHERE m.resource_id = NEW.resource_id
              AND lower(m.period) <= NEW.resolved_at
              AND upper(m.period) > NEW.resolved_at
              AND m.deleted_at IS NULL
        ) INTO v_maintenance;
        IF NOT v_maintenance THEN
            RAISE EXCEPTION 'EXTEND_MAINTENANCE resolution requires Maintenance covering the resolution instant';
        END IF;
    END IF;

    RETURN NULL;
END;
$function$;

CREATE OR REPLACE FUNCTION operations.assert_emergency_conversion_target_exists()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    e record;
BEGIN
    SELECT * INTO e FROM operations.emergency WHERE id = NEW.id;
    IF NOT FOUND OR e.status <> 'CONVERTED' THEN RETURN NULL; END IF;

    IF e.converted_to = 'MAINTENANCE' THEN
        IF NOT EXISTS (
            SELECT 1 FROM operations.maintenance_window m
            WHERE m.created_from_emergency_id = e.id
              AND m.resource_id = e.resource_id
              AND lower(m.period) <= e.ended_at
              AND upper(m.period) > e.ended_at
              AND m.deleted_at IS NULL
        ) THEN
            RAISE EXCEPTION 'MAINTENANCE conversion requires matching Maintenance for the same Resource at conversion time';
        END IF;
    ELSIF e.converted_to = 'CLOSURE' THEN
        IF NOT EXISTS (
            SELECT 1 FROM operations.venue_closure c
            WHERE c.venue_id = e.venue_id AND c.period @> e.ended_at
              AND c.deleted_at IS NULL
        ) THEN
            RAISE EXCEPTION 'CLOSURE conversion requires a matching Venue Closure covering conversion time';
        END IF;
    ELSIF e.converted_to = 'DISABLED' THEN
        IF NOT EXISTS (
            SELECT 1 FROM resource.resource r
            WHERE r.id = e.resource_id AND r.base_state = 'DISABLED'
        ) THEN
            RAISE EXCEPTION 'DISABLED conversion requires Resource base_state=DISABLED';
        END IF;
    ELSIF e.converted_to = 'ARCHIVED' THEN
        IF NOT EXISTS (
            SELECT 1 FROM resource.resource r
            WHERE r.id = e.resource_id AND r.base_state = 'ARCHIVED'
        ) THEN
            RAISE EXCEPTION 'ARCHIVED conversion requires Resource base_state=ARCHIVED';
        END IF;
    ELSIF e.converted_to = 'OPENING_HOURS_CHANGE' THEN
        -- The target is the existing Venue opening template/version. Require that the template
        -- was updated no earlier than the conversion resolution instant; affected-set behavior
        -- remains governed by the normal Opening Hours transaction.
        IF NOT EXISTS (
            SELECT 1 FROM venue.venue_opening_template t
            WHERE t.venue_id = e.venue_id AND t.updated_at >= transaction_timestamp()
        ) THEN
            RAISE EXCEPTION 'OPENING_HOURS_CHANGE conversion requires a committed Venue opening-template update';
        END IF;
    END IF;

    RETURN NULL;
END;
$function$;

CREATE OR REPLACE FUNCTION tournament.assert_resources_operational(
    p_tournament_id uuid,
    p_expected_hold_type text
)
RETURNS void
LANGUAGE plpgsql
AS $function$
DECLARE
    t tournament.tournament%ROWTYPE;
BEGIN
    SELECT * INTO t FROM tournament.tournament WHERE id = p_tournament_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Tournament % does not exist', p_tournament_id; END IF;

    IF (SELECT COUNT(*) FROM tournament.tournament_resource tr WHERE tr.tournament_id = t.id) NOT BETWEEN 1 AND 4 THEN
        RAISE EXCEPTION 'Tournament requires exactly 1 to 4 Resources';
    END IF;

    IF EXISTS (SELECT 1 FROM venue.venue_selling_block vb WHERE vb.venue_id = t.venue_id AND vb.active) THEN
        RAISE EXCEPTION 'Tournament Venue is under an Operational Selling Block';
    END IF;
    IF EXISTS (
        SELECT 1 FROM operations.venue_closure c
         WHERE c.venue_id = t.venue_id
           AND c.deleted_at IS NULL
           AND c.period && tstzrange(t.start_at,t.end_at,'[)')
    ) THEN
        RAISE EXCEPTION 'Tournament interval is covered by a Venue Closure';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tournament.tournament_resource tr
          JOIN resource.resource r ON r.id = tr.resource_id
          LEFT JOIN inventory.resource_reservation rr ON rr.id = tr.reservation_id
         WHERE tr.tournament_id = t.id
           AND tr.removed_at IS NULL
           AND (
               r.venue_id IS DISTINCT FROM t.venue_id
               OR r.activity IS DISTINCT FROM t.activity
               OR r.base_state <> 'ACTIVE'
               OR r.operational_restriction <> 'NONE'
               OR EXISTS (SELECT 1 FROM resource.resource_selling_block rb WHERE rb.resource_id = r.id AND rb.active)
               OR rr.id IS NULL
               OR rr.tournament_id IS DISTINCT FROM t.id
               OR rr.resource_id IS DISTINCT FROM r.id
               OR rr.reservation_type IS DISTINCT FROM p_expected_hold_type
               OR rr.active_flag IS DISTINCT FROM true
               OR rr.planned_start IS DISTINCT FROM t.start_at
               OR rr.planned_end IS DISTINCT FROM t.end_at
           )
    ) THEN
        RAISE EXCEPTION 'One or more assigned Tournament Resources/Holds are not operationally eligible';
    END IF;
END;
$function$;
