-- =====================================================================================================
-- E7-56 / physical model v1.1.36 — admin.raise_alert, the one producer of Admin Alerts
-- (Product Owner decisions 4 Oct 2026, E7-32 review, and 15 Oct 2026, E7-56; NFR-29, arch 11 section 5.4).
--
-- An alert must be able to commit in the same transaction as the command that found the problem (e.g.
-- SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR at startAt, E7-60), on either runtime connection. e7gz_app_runtime has no
-- INSERT or UPDATE on admin.admin_alert (security baseline, v1.1.29), so the write goes through this SECURITY DEFINER
-- producer, like the abuse-signal trigger (V202610021800) and admin.acknowledge_alert. One change, nothing else:
--
-- 1. admin.raise_alert(kind, severity, subject_type, subject_id, payload, correlation_id) inserts one open alert and
--    returns its id. Every rule stays with the table: admin.guard_admin_alert refuses a pre-acknowledged row, a future
--    created_at and a credential/secret key in the payload; ck_admin_alert_values refuses a blank kind, severity or
--    subject type and a non-object payload. kind and severity stay open text: SEV_1/SEV_2/SEV_3 (arch 11 section 5.4)
--    and the existing RISK abuse signal are all valid.
--
-- EXECUTE for e7gz_app_runtime and e7gz_admin_runtime comes from the security baseline, which grants every function
-- that is neither internal nor admin-only to both runtime roles and revokes it from PUBLIC.
--
-- Unchanged on purpose: admin.admin_alert, its trigger, constraints, indexes and grants (no INSERT or UPDATE for
-- e7gz_app_runtime), admin.acknowledge_alert, every other object and every data row.
-- Platform-lane function (Platform Administration): the Platform owner's change, for Product Owner review.
-- =====================================================================================================
CREATE FUNCTION admin.raise_alert(p_kind text, p_severity text, p_subject_type text, p_subject_id uuid,
                                  p_payload jsonb, p_correlation_id uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $function$
DECLARE
    v_id uuid := gen_random_uuid();
BEGIN
    INSERT INTO admin.admin_alert(id, kind, severity, subject_type, subject_id, payload, correlation_id)
    VALUES (v_id, p_kind, p_severity, p_subject_type, p_subject_id, p_payload, p_correlation_id);
    RETURN v_id;
END;
$function$;

COMMENT ON FUNCTION admin.raise_alert(text, text, text, uuid, jsonb, uuid) IS E'NFR-29, arch 11 section 5.4: the one producer of an open Admin Alert (E7-56). SECURITY DEFINER so either runtime role can raise an alert inside its own command; the table rules (admin.guard_admin_alert, ck_admin_alert_values) still apply.';
