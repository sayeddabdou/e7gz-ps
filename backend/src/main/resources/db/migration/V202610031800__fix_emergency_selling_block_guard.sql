-- =====================================================================================================
-- E7-30 / physical model v1.1.32 — Emergency selling-block guard fix (Product Owner decision 3 Oct 2026).
--
-- operations.guard_emergency_selling_block() guards both venue.venue_selling_block and
-- resource.resource_selling_block. Its UPDATE branch tested
--     TG_TABLE_SCHEMA = 'venue' AND NEW.venue_id IS DISTINCT FROM OLD.venue_id
--     TG_TABLE_SCHEMA = 'resource' AND NEW.resource_id IS DISTINCT FROM OLD.resource_id
-- PL/pgSQL resolves every record field of an expression before it evaluates it, so on the venue table
-- NEW.resource_id (and on the resource table NEW.venue_id) failed with 42703 "record "new" has no field".
-- No Emergency-linked selling block could be updated, so no Emergency could be ended or converted
-- (assert_emergency_block_consistency requires its block to close at the same instant).
--
-- The fix scopes every table-specific field access explicitly by its table: each NEW/OLD.venue_id inside
-- IF TG_TABLE_SCHEMA = 'venue', each NEW/OLD.resource_id inside IF TG_TABLE_SCHEMA = 'resource' (the
-- Emergency scope check's ELSE becomes an explicit resource IF, and the two subject-immutability tests are
-- nested). No business rule or error message changed. Unchanged on purpose: the signature, language,
-- SECURITY INVOKER, owner, grants and both triggers (they keep pointing at this function). No table, column,
-- index, constraint, trigger or data change. Engine-lane function (Operations): needs the Engine owner's
-- review.
-- =====================================================================================================
CREATE OR REPLACE FUNCTION operations.guard_emergency_selling_block()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    v_scope varchar(80);
    v_venue uuid;
    v_resource uuid;
    v_declared_at timestamptz;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.emergency_id IS NOT NULL THEN
            RAISE EXCEPTION 'Emergency selling-block history may not be deleted';
        END IF;
        RETURN OLD;
    END IF;

    IF NEW.emergency_id IS NOT NULL THEN
        SELECT e.scope, e.venue_id, e.resource_id, e.declared_at
          INTO v_scope, v_venue, v_resource, v_declared_at
          FROM operations.emergency e
         WHERE e.id = NEW.emergency_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Emergency % does not exist', NEW.emergency_id;
        END IF;

        IF TG_TABLE_SCHEMA = 'venue' THEN
            IF v_scope <> 'VENUE' OR NEW.venue_id IS DISTINCT FROM v_venue THEN
                RAISE EXCEPTION 'Venue selling block must reference a VENUE Emergency for the same Venue';
            END IF;
        END IF;
        IF TG_TABLE_SCHEMA = 'resource' THEN
            IF v_scope <> 'RESOURCE' OR NEW.resource_id IS DISTINCT FROM v_resource THEN
                RAISE EXCEPTION 'Resource selling block must reference a RESOURCE Emergency for the same Resource';
            END IF;
        END IF;

        IF TG_OP = 'INSERT' THEN
            NEW.started_at := v_declared_at;
            NEW.active := true;
            NEW.ended_at := NULL;
        ELSE
            IF NEW.emergency_id IS DISTINCT FROM OLD.emergency_id
               OR NEW.started_at IS DISTINCT FROM OLD.started_at
               OR NEW.cause IS DISTINCT FROM OLD.cause THEN
                RAISE EXCEPTION 'Emergency selling-block identity/start/cause are immutable';
            END IF;
            IF TG_TABLE_SCHEMA = 'venue' THEN
                IF NEW.venue_id IS DISTINCT FROM OLD.venue_id THEN
                    RAISE EXCEPTION 'Venue selling-block subject is immutable';
                END IF;
            END IF;
            IF TG_TABLE_SCHEMA = 'resource' THEN
                IF NEW.resource_id IS DISTINCT FROM OLD.resource_id THEN
                    RAISE EXCEPTION 'Resource selling-block subject is immutable';
                END IF;
            END IF;
            IF OLD.active = false AND (NEW.active OR NEW.ended_at IS DISTINCT FROM OLD.ended_at) THEN
                RAISE EXCEPTION 'Closed Emergency selling block cannot be reactivated or rewritten';
            END IF;
            IF OLD.active = true AND NEW.active = true AND NEW.ended_at IS NOT NULL THEN
                RAISE EXCEPTION 'Active selling block cannot have ended_at';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;
