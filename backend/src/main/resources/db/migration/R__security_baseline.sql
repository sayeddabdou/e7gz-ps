-- Flyway repeatable copy of docs/04-erd/physical/E7GZ-PS-security-baseline-v1.1.27.sql (E7-10).
-- The line below changes on every run, so Flyway re-applies this idempotent baseline after every migration (arch 04 §5).
-- ${flyway:timestamp}

-- E7GZ PS — deployment security baseline for physical model v1.1.27
-- Run once after the baseline migration (V1__e7gz_ps_baseline_v1_1_27.sql), as the owner of the objects.
-- Replaces the per-version companions/deltas (v1.1.23 … v1.1.27). Idempotent.
-- Passwords are set by operations (secret store), never here.

DO $roles$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='e7gz_app_runtime')   THEN CREATE ROLE e7gz_app_runtime LOGIN;   END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='e7gz_admin_runtime') THEN CREATE ROLE e7gz_admin_runtime LOGIN; END IF;
END $roles$;

DO $grants$
DECLARE
  s text; r text; f regprocedure;
  schemas text[] := ARRAY['iam','geo','venue','resource','pricing','inventory','operations','booking','attendance',
                          'tournament','trust','loyalty','review','notification','config','audit','admin','technical'];
  -- Producer-only tables: written exclusively through their producer functions (ADR-024, ADR-025, ADR-026).
  producer_only text[] := ARRAY['iam.account_deletion_request','iam.account_anonymisation','iam.username_change','review.review_report'];
  -- The only tables where a runtime DELETE is a legitimate Product/technical operation; every other table is history.
  deletable text[] := ARRAY['venue.venue_image','venue.venue_activity','venue.venue_opening_day','venue.operating_session',
                            'pricing.resource_price_override','inventory.venue_activity_availability',
                            'iam.auth_refresh_token','iam.security_rate_limit_counter'];
  -- Never callable by a runtime role: private write-token and context-creation internals.
  internal text[] := ARRAY['technical.enter_write','technical.leave_write','loyalty.enter_write','loyalty.leave_write',
                           'trust.secure_created_context','review.remove_reviews_of_deleted_account'];
  -- v1.1.38 (E7-66, Product Owner decision 23 Oct 2026): DEFERRED until the payment gateway (ADR-018). A Booking Fee
  -- receipt is what turns a refund decision into Wallet money (loyalty.settle_refund), so no runtime role may record
  -- one. The payment gateway's settlement-adapter role is granted it when payment collection ships.
  deferred text[] := ARRAY['loyalty.record_fee_receipt'];
  -- Platform Admin producers: admin runtime only.
  admin_only text[] := ARRAY['admin.begin_command','admin.bind_admin','admin.acknowledge_alert','trust.begin_admin_command',
                             'loyalty.begin_admin_command','loyalty.configure','loyalty.adjust_points','loyalty.invalidate_referral',
                             'loyalty.assess_service_loss','loyalty.confirm_service_loss','loyalty.record_service_loss_not_established',
                             'config.change_configuration','iam.anonymise_user','iam.complete_account_deletion_request',
                             'review.moderate','review.resolve_report','notification.recover_dead_letter'];
  qn text;
BEGIN
 FOREACH r IN ARRAY ARRAY['e7gz_app_runtime','e7gz_admin_runtime'] LOOP
   FOREACH s IN ARRAY schemas LOOP
     EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I',s,r);
     EXECUTE format('GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA %I TO %I',s,r);
     EXECUTE format('REVOKE DELETE, TRUNCATE ON ALL TABLES IN SCHEMA %I FROM %I',s,r);
     EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I',s,r);
   END LOOP;
   FOREACH qn IN ARRAY producer_only LOOP EXECUTE format('REVOKE INSERT, UPDATE ON %s FROM %I',qn,r); END LOOP;
   FOREACH qn IN ARRAY deletable     LOOP EXECUTE format('GRANT DELETE ON %s TO %I',qn,r);           END LOOP;
 END LOOP;

 -- v1.1.29 (E7-23; Sprint 0 review S-6, Product Owner decision 2 Oct 2026): the public API's role cannot write the
 -- Platform Administration tables (arch 03 section 1, module 19) and cannot read admin credentials. It keeps SELECT on
 -- iam.admin_account (id, is_active) only, which its SECURITY INVOKER triggers read to check an ADMIN actor. Admin
 -- alerts are written only by SECURITY DEFINER functions (the abuse-signal trigger since migration V202610021800).
 -- Admin login and every admin write use e7gz_admin_runtime. Revoking the table-level SELECT also clears column grants,
 -- so the column grant follows it on every run.
 REVOKE SELECT, INSERT, UPDATE ON iam.admin_account FROM e7gz_app_runtime;
 GRANT SELECT (id, is_active) ON iam.admin_account TO e7gz_app_runtime;
 REVOKE INSERT, UPDATE ON admin.admin_alert FROM e7gz_app_runtime;

 FOR f, qn IN
   SELECT p.oid::regprocedure, n.nspname||'.'||p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
    WHERE n.nspname = ANY(schemas) AND p.prokind='f'
 LOOP
   EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC',f);
   IF qn = ANY(internal) OR qn = ANY(deferred) THEN
     EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM e7gz_app_runtime, e7gz_admin_runtime',f);
   ELSIF qn = ANY(admin_only) THEN
     EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM e7gz_app_runtime',f);
     EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO e7gz_admin_runtime',f);
   ELSE
     EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO e7gz_app_runtime, e7gz_admin_runtime',f);
   END IF;
 END LOOP;
END $grants$;
