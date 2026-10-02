-- =====================================================================================================
-- E7-23 / physical model v1.1.29 — admin tables closed to the public API's role (Sprint 0 review S-6,
-- Product Owner decision 2 Oct 2026).
--
-- operations.raise_resource_emergency_tournament_abuse_alert() is the AFTER INSERT trigger on
-- tournament.tournament_history that writes the O11 abuse signal into admin.admin_alert. It ran with the
-- caller's rights, so e7gz_app_runtime needed INSERT on admin.admin_alert. It now runs as its owner, like
-- every other admin-table writer (admin.guard_admin_alert, admin.acknowledge_alert), with a fixed search_path;
-- the security baseline then revokes INSERT and UPDATE on admin.admin_alert from e7gz_app_runtime.
--
-- Unchanged on purpose: the function body (every name in it is schema-qualified), signature, owner, grants
-- and the trigger. No table, column, index, constraint or data change.
-- Engine-lane trigger (tournament history): needs the Engine owner's review.
-- =====================================================================================================
ALTER FUNCTION operations.raise_resource_emergency_tournament_abuse_alert()
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp;
