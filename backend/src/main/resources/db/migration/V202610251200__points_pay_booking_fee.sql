-- =====================================================================================================
-- E7-68 / physical model v1.1.40 — Points pay the Booking Fee; the Booking Fee is per booking or per hour (PCR-010,
-- Product Owner decisions 25 Oct 2026; amends FR-PTS-12, FR-PTS-13, FR-ADM-10 and PCR-009).
--
--   1. Configuration: booking.fee_mode (PER_BOOKING | PER_HOUR), booking.fee_per_hour (EGP) and
--      loyalty.points_per_fee_unit (even, from 2), each with its technical bootstrap version at the launch value
--      (PER_BOOKING, 0, 10000); config.effective_text; config.booking_fee_for(nominal minutes, instant), the one
--      definition of a booking's fee (per booking, or the hourly fee x nominal minutes / 60, half-up to 2 places).
--   2. booking.validate_online_booking_intake: its v1.1.37 definition with the fee check on config.booking_fee_for.
--   3. Loyalty configuration: the new key in config.is_loyalty_key, loyalty.config_write_guard,
--      loyalty.configuration_at and loyalty.configure; loyalty.configure refuses an odd Points per fee unit, and runs
--      inside a Platform Admin command whose shared lock scope is empty (a configuration-only Admin transaction,
--      so the Idempotency-Key record commits with it) as well as on its own.
--   4. loyalty.points_transaction: the causes BOOKING_FEE_PAYMENT (an exact debit) and BOOKING_FEE_REFUND (the same
--      Points given back, compensating the payment). Neither names a Booking in booking_id: that column stays the
--      Booking a reward belongs to; the Booking of a fee movement is its payment row's.
--   5. loyalty.booking_fee_points_payment: what Points paid of one Booking's fee (append-only); and
--      loyalty.booking_fee_refund.points_transaction_id, the Points a refund gave back.
--   6. loyalty.write_guard: the new table is append-only.
--   7. loyalty.post_points: its V1 definition plus the fee causes' rules.
--   8. loyalty.fee_points_cover (the automatic rule), loyalty.fee_money_due, the producer loyalty.pay_booking_fee
--      (Points first, then the Wallet pays the whole remainder or nothing), loyalty.pay_fee_from_wallet as its
--      Wallet part, and loyalty.preview_fee_payment (the quote).
--   9. Refunds: loyalty.record_refund gives the Points back with the decision; loyalty.settle_refund and
--      loyalty.record_fee_receipt work on the money part only.
--  10. loyalty.assert_booking and loyalty.assert_user: their previous definitions plus the Points-payment bindings.
--
-- Every existing row satisfies the widened checks: no backfill. At the launch values (PER_BOOKING, EGP 0) every
-- Booking's fee is EGP 0 as before, and nothing is paid. Grants: the security baseline grants EXECUTE on the new
-- functions to both runtime roles after this migration, and SELECT/INSERT/UPDATE on the new table.
-- =====================================================================================================

-- ---------- 1. Configuration ----------
INSERT INTO config.configuration_key(key,value_type,min_value,max_value,units,allowed_values,owner_module,source_reference)
VALUES
 ('booking.fee_mode','TEXT',NULL,NULL,NULL,'["PER_BOOKING","PER_HOUR"]'::jsonb,'booking','PCR-010 item 1 Booking Fee mode; launch value PER_BOOKING (v1.1.40 seed)'),
 ('booking.fee_per_hour','NUMERIC',0,NULL,'EGP',NULL,'booking','PCR-010 items 1-2 per-hour Booking Fee; launch value EGP 0 (v1.1.40 seed)'),
 ('loyalty.points_per_fee_unit','INTEGER',2,2147483646,'points',NULL,'loyalty','PCR-010 item 4 Points per fee unit, an even number; launch value 10000 (v1.1.40 seed)')
ON CONFLICT(key) DO NOTHING;

CREATE OR REPLACE FUNCTION config.effective_text(p_key text,p_at timestamptz)
RETURNS text LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v jsonb;
BEGIN
 IF p_at IS NULL THEN RAISE EXCEPTION 'Configuration lookup requires an instant'; END IF;
 SELECT c.new_value INTO v FROM config.configuration_version c
  WHERE c.key=p_key AND c.effective_from<=p_at ORDER BY c.effective_from DESC,c.version_position DESC LIMIT 1;
 IF v IS NULL OR jsonb_typeof(v)<>'string' THEN RAISE EXCEPTION 'No text configuration % effective at %',p_key,p_at; END IF;
 RETURN v #>> '{}';
END;
$function$;

-- The Booking Fee of a booking of p_minutes nominal minutes created at p_at (FR-PTS-13 as amended by PCR-010): the
-- per-booking fee, or the per-hour fee x nominal minutes / 60, half-up to 2 places (FR-BOK-07: nominal minutes only).
-- Unknown minutes (NULL) have a fee per booking only; per hour the amount is then NULL.
CREATE OR REPLACE FUNCTION config.booking_fee_for(p_minutes integer,p_at timestamptz)
RETURNS TABLE(fee_mode text,fee_rate numeric,fee_amount numeric)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE m text; r numeric;
BEGIN
 IF p_minutes<=0 THEN RAISE EXCEPTION 'Booking Fee lookup requires positive nominal booked minutes'; END IF;
 m:=config.effective_text('booking.fee_mode',p_at);
 IF m='PER_BOOKING' THEN r:=config.effective_numeric('booking.fee',p_at);
 ELSIF m='PER_HOUR' THEN r:=config.effective_numeric('booking.fee_per_hour',p_at);
 ELSE RAISE EXCEPTION 'Unknown Booking Fee mode %',m;
 END IF;
 RETURN QUERY SELECT m,r,CASE WHEN m='PER_BOOKING' THEN r ELSE round(r*p_minutes/60.0,2) END;
END;
$function$;

-- ---------- 2. booking.validate_online_booking_intake: its v1.1.37 definition, the fee check on config.booking_fee_for ----------
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
 -- §5.1, PCR-010 (v1.1.40): the stored fee is the Booking Fee of this booking's nominal minutes at creation.
 IF NEW.booking_fee_snapshot IS DISTINCT FROM (SELECT f.fee_amount FROM config.booking_fee_for(NEW.nominal_booked_minutes,NEW.created_at) f) THEN
   RAISE EXCEPTION 'booking_fee_snapshot must equal the Global Booking Fee at creation';
 END IF;
 RETURN NEW;
END;
$function$;

-- ---------- 3. Loyalty configuration: the Points per fee unit is a Loyalty key ----------
CREATE OR REPLACE FUNCTION config.is_loyalty_key(p_key text)
RETURNS boolean LANGUAGE sql IMMUTABLE AS $function$
 SELECT p_key IN ('loyalty.attended_points','loyalty.referral_points','loyalty.wallet_refunds_enabled','loyalty.points_per_fee_unit');
$function$;
CREATE OR REPLACE FUNCTION loyalty.config_write_guard()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE k text;
BEGIN
 IF TG_OP='DELETE' THEN k:=OLD.key; ELSE k:=NEW.key; END IF;
 IF k IN ('loyalty.attended_points','loyalty.referral_points','loyalty.wallet_refunds_enabled','loyalty.points_per_fee_unit') OR
    (TG_OP='UPDATE' AND OLD.key IN ('loyalty.attended_points','loyalty.referral_points','loyalty.wallet_refunds_enabled','loyalty.points_per_fee_unit')) THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Loyalty config history cannot be removed'; END IF;
  IF TG_TABLE_NAME='configuration_version' THEN PERFORM loyalty.require_write('config.configuration_version',NEW.id::text);
  ELSE PERFORM loyalty.require_write('config.system_configuration',NEW.key); END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$function$;
CREATE OR REPLACE FUNCTION loyalty.configuration_at(p_key text,p_at timestamptz)
RETURNS TABLE(version_id uuid,value jsonb)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
BEGIN
 IF p_at IS NULL OR p_key NOT IN ('loyalty.attended_points','loyalty.referral_points','loyalty.wallet_refunds_enabled','loyalty.points_per_fee_unit') THEN RAISE EXCEPTION 'Invalid config lookup'; END IF;
 RETURN QUERY SELECT c.id,c.new_value FROM config.configuration_version c WHERE c.key=p_key AND c.effective_from<=p_at ORDER BY c.effective_from DESC,c.id DESC LIMIT 1;
 IF NOT FOUND THEN RAISE EXCEPTION 'No historical configuration for % at %',p_key,p_at; END IF;
END;
$function$;
CREATE OR REPLACE FUNCTION loyalty.configure(p_key text,p_value jsonb,p_admin uuid,p_request uuid,p_reason text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE now_at timestamptz; existing config.configuration_version%ROWTYPE; oldv jsonb; tok uuid; aid uuid:=gen_random_uuid();
BEGIN
 IF p_key IS NULL OR p_key NOT IN ('loyalty.attended_points','loyalty.referral_points','loyalty.wallet_refunds_enabled','loyalty.points_per_fee_unit')
    OR p_request IS NULL OR NULLIF(btrim(p_reason),'') IS NULL THEN RAISE EXCEPTION 'Invalid configuration command'; END IF;
 PERFORM a.id FROM iam.admin_account a WHERE a.id=p_admin AND a.is_active FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Active Admin required'; END IF;
 IF p_key='loyalty.wallet_refunds_enabled' THEN
   IF jsonb_typeof(p_value) IS DISTINCT FROM 'boolean' THEN RAISE EXCEPTION 'Expected Boolean feature flag'; END IF;
 ELSE
   IF jsonb_typeof(p_value) IS DISTINCT FROM 'number' OR p_value::text !~ '^[0-9]+$' OR p_value::text::numeric>2147483647 THEN RAISE EXCEPTION 'Expected nonnegative bounded integer'; END IF;
   -- v1.1.40 (PCR-010 item 4): a half hour costs half a unit, so the unit is an even whole number.
   IF p_key='loyalty.points_per_fee_unit' AND (p_value::text::numeric<2 OR mod(p_value::text::numeric,2)<>0) THEN
     RAISE EXCEPTION 'Points per fee unit must be an even whole number from 2';
   END IF;
 END IF;
 -- v1.1.40: a Platform Admin command that locks nothing is a configuration-only Admin transaction too, so the
 -- Idempotency-Key record commits with the change; a business command (anything in its lock scope) still may not.
 IF to_regclass('pg_temp.e7gz_tournament_command_context') IS NOT NULL THEN
   IF to_regclass('pg_temp.e7gz_admin_command_context') IS NULL
      OR EXISTS(SELECT 1 FROM pg_temp.e7gz_tournament_command_context c WHERE c.singleton AND (cardinality(c.user_ids)>0
        OR cardinality(c.venue_ids)>0 OR cardinality(c.resource_ids)>0 OR cardinality(c.tournament_ids)>0
        OR cardinality(c.new_tournament_ids)>0))
      OR NOT EXISTS(SELECT 1 FROM admin.command_context() a WHERE a.admin_id=p_admin) THEN
     RAISE EXCEPTION 'Configuration is a separate Admin transaction, not a mid-business-command change';
   END IF;
 END IF;
 PERFORM pg_advisory_xact_lock(1123,1);
 SELECT * INTO existing FROM config.configuration_version WHERE id=p_request;
 IF FOUND THEN
  IF existing.key IS DISTINCT FROM p_key OR existing.new_value IS DISTINCT FROM p_value OR existing.admin_id IS DISTINCT FROM p_admin OR existing.reason IS DISTINCT FROM p_reason THEN RAISE EXCEPTION 'Config request ID reused'; END IF;
  RETURN existing.id;
 END IF;
 now_at:=clock_timestamp();
 SELECT value INTO oldv FROM config.system_configuration WHERE key=p_key;
 tok:=loyalty.enter_write('config.configuration_version',p_request::text);
 -- v1.1.25 CFG-05: explicit server change time and correlation (= request id, as used by its audit).
 INSERT INTO config.configuration_version(id,key,old_value,new_value,effective_from,admin_id,reason,changed_at,correlation_id)
 VALUES(p_request,p_key,oldv,p_value,now_at,p_admin,p_reason,now_at,p_request);
 PERFORM loyalty.leave_write(tok);
 tok:=loyalty.enter_write('config.system_configuration',p_key);
 INSERT INTO config.system_configuration(key,value,updated_at) VALUES(p_key,p_value,now_at)
 ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value,updated_at=EXCLUDED.updated_at;
 PERFORM loyalty.leave_write(tok);
 tok:=loyalty.enter_write('audit.audit_event',aid::text);
 INSERT INTO audit.audit_event(id,occurred_at,actor_type,actor_id,action,subject_type,subject_id,reason,correlation_id,payload)
 VALUES(aid,now_at,'ADMIN',p_admin,'LOYALTY_CONFIGURATION_CHANGED','CONFIGURATION',p_request,p_reason,p_request,
   jsonb_build_object('key',p_key,'oldValue',oldv,'newValue',p_value));
 PERFORM loyalty.leave_write(tok);
 RETURN p_request;
END;
$function$;

-- Launch values as the initial technical configuration history (the first version of a key may be -infinity).
DO $seed_pcr010_config$
DECLARE k text; val jsonb; vid uuid; tok uuid;
BEGIN
 FOREACH k IN ARRAY ARRAY['booking.fee_mode','booking.fee_per_hour','loyalty.points_per_fee_unit'] LOOP
   IF NOT EXISTS(SELECT 1 FROM config.configuration_version WHERE key=k) THEN
     IF EXISTS(SELECT 1 FROM config.system_configuration WHERE key=k) THEN
       RAISE EXCEPTION 'Existing current config % lacks historical provenance',k;
     END IF;
     val:=CASE k WHEN 'booking.fee_mode' THEN '"PER_BOOKING"'::jsonb WHEN 'booking.fee_per_hour' THEN '0'::jsonb ELSE '10000'::jsonb END;
     vid:=gen_random_uuid();
     IF config.is_loyalty_key(k) THEN
       tok:=loyalty.enter_write('config.configuration_version',vid::text);
       INSERT INTO config.configuration_version(id,key,new_value,effective_from,reason)
         VALUES(vid,k,val,'-infinity','Initial launch configuration from PCR-010; technical bootstrap');
       PERFORM loyalty.leave_write(tok);
       tok:=loyalty.enter_write('config.system_configuration',k);
       INSERT INTO config.system_configuration(key,value,updated_at) VALUES(k,val,clock_timestamp());
       PERFORM loyalty.leave_write(tok);
     ELSE
       tok:=technical.enter_write('config.configuration_version',vid::text);
       INSERT INTO config.configuration_version(id,key,new_value,effective_from,reason)
         VALUES(vid,k,val,'-infinity','Initial launch configuration from PCR-010; technical bootstrap');
       PERFORM technical.leave_write(tok);
       tok:=technical.enter_write('config.system_configuration',k);
       INSERT INTO config.system_configuration(key,value,updated_at) VALUES(k,val,clock_timestamp());
       PERFORM technical.leave_write(tok);
     END IF;
   END IF;
 END LOOP;
END;
$seed_pcr010_config$;

-- ---------- 4. Points causes of a Booking Fee paid with Points ----------
ALTER TABLE loyalty.points_transaction DROP CONSTRAINT ck_points_transaction_cause;
ALTER TABLE loyalty.points_transaction ADD CONSTRAINT ck_points_transaction_cause CHECK (cause IN ('ATTENDED_COMPLETION','REFERRAL_SIGNUP','COMPENSATION','ADMIN','BOOKING_FEE_PAYMENT','BOOKING_FEE_REFUND'));
ALTER TABLE loyalty.points_transaction DROP CONSTRAINT ck_points_tx_origin;
ALTER TABLE loyalty.points_transaction ADD CONSTRAINT ck_points_tx_origin CHECK ((cause='ATTENDED_COMPLETION' AND booking_id IS NOT NULL AND referral_id IS NULL AND source_trust_event_id IS NOT NULL AND compensates_transaction_id IS NULL AND amount>=0) OR (cause='REFERRAL_SIGNUP' AND referral_id IS NOT NULL AND booking_id IS NULL AND source_trust_event_id IS NULL AND compensates_transaction_id IS NULL AND amount>=0) OR (cause='COMPENSATION' AND compensates_transaction_id IS NOT NULL AND ((booking_id IS NOT NULL AND referral_id IS NULL AND source_trust_event_id IS NOT NULL) OR (booking_id IS NULL AND referral_id IS NOT NULL AND source_trust_event_id IS NULL))) OR (cause='ADMIN' AND booking_id IS NULL AND referral_id IS NULL AND source_trust_event_id IS NULL AND compensates_transaction_id IS NULL AND admin_id IS NOT NULL AND NULLIF(btrim(reason),'') IS NOT NULL) OR (cause='BOOKING_FEE_PAYMENT' AND booking_id IS NULL AND referral_id IS NULL AND source_trust_event_id IS NULL AND compensates_transaction_id IS NULL AND admin_id IS NULL AND amount<0 AND amount=requested_amount) OR (cause='BOOKING_FEE_REFUND' AND booking_id IS NULL AND referral_id IS NULL AND source_trust_event_id IS NULL AND compensates_transaction_id IS NOT NULL AND amount>0));
COMMENT ON COLUMN loyalty.points_transaction.cause IS E'ATTENDED_COMPLETION · REFERRAL_SIGNUP · COMPENSATION · ADMIN · BOOKING_FEE_PAYMENT · BOOKING_FEE_REFUND (v1.1.40, PCR-010)';

-- ---------- 5. What Points paid of a Booking Fee, and what a refund gave back ----------
CREATE TABLE loyalty.booking_fee_points_payment (
	id uuid NOT NULL,
	booking_id uuid NOT NULL,
	user_id uuid NOT NULL,
	fee_mode text NOT NULL,
	fee_rate numeric(12,2) NOT NULL,
	fee_amount numeric(12,2) NOT NULL,
	points_per_fee_unit integer NOT NULL,
	configuration_version_id uuid NOT NULL,
	half_hours_paid integer,
	points_used integer NOT NULL,
	amount_paid numeric(12,2) NOT NULL,
	points_transaction_id uuid NOT NULL,
	paid_at timestamp with time zone NOT NULL,
	correlation_id uuid NOT NULL,
	CONSTRAINT booking_fee_points_payment_pk PRIMARY KEY (id),
	CONSTRAINT uq_fee_points_payment_booking UNIQUE (booking_id),
	CONSTRAINT uq_fee_points_payment_tx UNIQUE (points_transaction_id),
	CONSTRAINT ck_fee_points_payment_shape CHECK (fee_mode IN ('PER_BOOKING','PER_HOUR') AND fee_rate>0 AND fee_amount>0 AND points_per_fee_unit>=2 AND mod(points_per_fee_unit,2)=0 AND amount_paid>0 AND amount_paid<=fee_amount AND ((fee_mode='PER_BOOKING' AND half_hours_paid IS NULL AND points_used=points_per_fee_unit AND fee_rate=fee_amount AND amount_paid=fee_amount) OR (fee_mode='PER_HOUR' AND half_hours_paid BETWEEN 1 AND 12 AND points_used::bigint=half_hours_paid::bigint*(points_per_fee_unit/2) AND amount_paid=round(fee_rate*half_hours_paid/2.0,2))))
);
COMMENT ON TABLE loyalty.booking_fee_points_payment IS E'APPEND-ONLY. What Points paid of one Online Booking\'s Booking Fee, at its creation (PCR-010): one fee unit (points_per_fee_unit, even) pays one hour of fee (PER_HOUR, a half hour costs half a unit) or the whole fee (PER_BOOKING). Written only by loyalty.pay_booking_fee.';
COMMENT ON COLUMN loyalty.booking_fee_points_payment.half_hours_paid IS E'PER_HOUR only: the half hours the Points paid; NULL for PER_BOOKING';
COMMENT ON COLUMN loyalty.booking_fee_points_payment.amount_paid IS E'EGP of the fee the Points paid; the Wallet or the payment provider pays fee_amount - amount_paid';
ALTER TABLE loyalty.booking_fee_points_payment ADD CONSTRAINT fk_fee_points_payment_booking FOREIGN KEY (booking_id)
REFERENCES booking.booking (id) MATCH SIMPLE ON DELETE RESTRICT ON UPDATE NO ACTION;
ALTER TABLE loyalty.booking_fee_points_payment ADD CONSTRAINT fk_fee_points_payment_user FOREIGN KEY (user_id)
REFERENCES iam.user_account (id) MATCH SIMPLE ON DELETE RESTRICT ON UPDATE NO ACTION;
ALTER TABLE loyalty.booking_fee_points_payment ADD CONSTRAINT fk_fee_points_payment_tx FOREIGN KEY (points_transaction_id)
REFERENCES loyalty.points_transaction (id) MATCH SIMPLE ON DELETE RESTRICT ON UPDATE NO ACTION;
ALTER TABLE loyalty.booking_fee_points_payment ADD CONSTRAINT fk_fee_points_payment_config FOREIGN KEY (configuration_version_id)
REFERENCES config.configuration_version (id) MATCH SIMPLE ON DELETE RESTRICT ON UPDATE NO ACTION;
CREATE INDEX ix_fk_booking_fee_points_payment_user_id ON loyalty.booking_fee_points_payment USING btree (user_id) WITH (FILLFACTOR = 90);
CREATE INDEX ix_fk_booking_fee_points_payment_configuration_version_id ON loyalty.booking_fee_points_payment USING btree (configuration_version_id) WITH (FILLFACTOR = 90);
CREATE TRIGGER tr_123_write_guard BEFORE INSERT OR UPDATE OR DELETE ON loyalty.booking_fee_points_payment FOR EACH ROW EXECUTE FUNCTION loyalty.write_guard();
CREATE TRIGGER tr_123_no_truncate BEFORE TRUNCATE ON loyalty.booking_fee_points_payment FOR EACH STATEMENT EXECUTE FUNCTION technical.reject_append_only_mutation();
CREATE CONSTRAINT TRIGGER tr_123_final_integrity AFTER INSERT OR UPDATE OR DELETE ON loyalty.booking_fee_points_payment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION loyalty.final_integrity_trigger();

ALTER TABLE loyalty.booking_fee_refund ADD COLUMN points_transaction_id uuid;
COMMENT ON COLUMN loyalty.booking_fee_refund.points_transaction_id IS E'The BOOKING_FEE_REFUND Points transaction that gave back the Points used, written with the decision (v1.1.40, PCR-010)';
ALTER TABLE loyalty.booking_fee_refund ADD CONSTRAINT ck_fee_refund_points CHECK (points_transaction_id IS NULL OR amount>0);
ALTER TABLE loyalty.booking_fee_refund ADD CONSTRAINT fk_fee_refund_points_tx FOREIGN KEY (points_transaction_id)
REFERENCES loyalty.points_transaction (id) MATCH SIMPLE ON DELETE RESTRICT ON UPDATE NO ACTION;
CREATE UNIQUE INDEX ux_fee_refund_points_tx ON loyalty.booking_fee_refund USING btree (points_transaction_id) WITH (FILLFACTOR = 90) WHERE (points_transaction_id IS NOT NULL);

-- ---------- 6. loyalty.write_guard: the Points payment record is append-only ----------
CREATE OR REPLACE FUNCTION loyalty.write_guard()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_key text;
BEGIN
 IF TG_OP IN ('DELETE','TRUNCATE') THEN RAISE EXCEPTION 'Loyalty rows cannot be deleted/truncated'; END IF;
 IF TG_OP='UPDATE' AND TG_TABLE_NAME IN ('points_transaction','wallet_transaction','reward_application','booking_fee_receipt','booking_fee_points_payment') THEN
   RAISE EXCEPTION 'Loyalty ledger/provenance is append-only';
 END IF;
 IF TG_TABLE_NAME IN ('points_account','wallet_account') THEN v_key:=NEW.user_id::text; ELSE v_key:=NEW.id::text; END IF;
 PERFORM loyalty.require_write(TG_TABLE_SCHEMA||'.'||TG_TABLE_NAME,v_key);
 IF TG_OP='UPDATE' AND TG_TABLE_NAME IN ('points_account','wallet_account') THEN
   IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN RAISE EXCEPTION 'Account identity is immutable'; END IF;
 END IF;
 RETURN NEW;
END;
$function$;

-- ---------- 7. loyalty.post_points: its V1 definition plus the Booking Fee causes ----------
CREATE OR REPLACE FUNCTION loyalty.post_points(
 p_user uuid,p_requested integer,p_cause text,p_key text,p_booking uuid,p_referral uuid,
 p_event uuid,p_compensates uuid,p_snapshot integer,p_config uuid,p_config_at timestamptz,p_reason text
)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE
 v_ctx record; v_existing loyalty.points_transaction%ROWTYPE; v_original loyalty.points_transaction%ROWTYPE;
 v_balance integer; v_amount integer; v_shortfall integer; v_after bigint;
 v_id uuid:=gen_random_uuid(); v_audit uuid; v_outbox bigint; v_token uuid; v_payload jsonb;
BEGIN
 SELECT * INTO STRICT v_ctx FROM loyalty.command_context(p_user);
 IF p_requested IS NULL OR p_requested=-2147483648 OR NULLIF(btrim(p_key),'') IS NULL THEN RAISE EXCEPTION 'Invalid Points request'; END IF;
 SELECT * INTO v_existing FROM loyalty.points_transaction x WHERE x.operation_key=p_key;
 IF FOUND THEN
   IF v_existing.points_account_id IS DISTINCT FROM p_user OR v_existing.requested_amount IS DISTINCT FROM p_requested
      OR v_existing.cause IS DISTINCT FROM p_cause OR v_existing.booking_id IS DISTINCT FROM p_booking
      OR v_existing.referral_id IS DISTINCT FROM p_referral OR v_existing.source_trust_event_id IS DISTINCT FROM p_event
      OR v_existing.compensates_transaction_id IS DISTINCT FROM p_compensates
      OR v_existing.admin_id IS DISTINCT FROM v_ctx.admin_id OR v_existing.reason IS DISTINCT FROM p_reason
      OR v_existing.granted_value_snapshot IS DISTINCT FROM p_snapshot
      OR v_existing.configuration_version_id IS DISTINCT FROM p_config OR v_existing.configuration_as_of IS DISTINCT FROM p_config_at THEN
     RAISE EXCEPTION 'Idempotency key reused with a different Points request';
   END IF;
   RETURN v_existing.id;
 END IF;
 IF p_cause='ADMIN' AND v_ctx.admin_id IS NULL THEN RAISE EXCEPTION 'Points Admin adjustment requires Admin command'; END IF;
 -- v1.1.40 (PCR-010): a Booking Fee movement names its Booking through its payment, never the reward columns.
 IF p_cause IN ('BOOKING_FEE_PAYMENT','BOOKING_FEE_REFUND') AND (p_booking IS NOT NULL OR p_referral IS NOT NULL OR p_event IS NOT NULL) THEN
   RAISE EXCEPTION 'A Booking Fee Points movement is not a reward';
 END IF;
 IF p_compensates IS NOT NULL THEN
   SELECT * INTO STRICT v_original FROM loyalty.points_transaction WHERE id=p_compensates;
   -- v1.1.40 (PCR-010): a Booking Fee refund gives back exactly the Points its payment used.
   IF p_cause='BOOKING_FEE_REFUND' THEN
     IF v_original.points_account_id IS DISTINCT FROM p_user OR v_original.cause<>'BOOKING_FEE_PAYMENT'
        OR p_requested IS DISTINCT FROM -v_original.amount THEN
       RAISE EXCEPTION 'A Booking Fee Points refund returns exactly the Points its payment used';
     END IF;
   ELSIF v_original.points_account_id IS DISTINCT FROM p_user OR v_original.booking_id IS DISTINCT FROM p_booking
      OR v_original.referral_id IS DISTINCT FROM p_referral OR v_original.amount<0
      OR v_original.cause IN ('BOOKING_FEE_PAYMENT','BOOKING_FEE_REFUND') THEN
     RAISE EXCEPTION 'Compensation must reference the same-account original positive reward';
   END IF;
 END IF;
 SELECT a.balance INTO STRICT v_balance FROM loyalty.points_account a WHERE a.user_id=p_user FOR UPDATE;
 -- v1.1.40 (PCR-010): Points pay a fee only when the balance covers the whole debit; never the zero-floor cap.
 IF p_cause='BOOKING_FEE_PAYMENT' AND (p_requested>=0 OR -p_requested::bigint>v_balance) THEN
   RAISE EXCEPTION 'Points do not cover this Booking Fee payment';
 END IF;
 IF p_cause='BOOKING_FEE_REFUND' AND p_requested<=0 THEN RAISE EXCEPTION 'A Booking Fee Points refund adds Points'; END IF;
 v_amount:=CASE WHEN p_requested<0 THEN GREATEST(p_requested,-v_balance) ELSE p_requested END;
 v_shortfall:=CASE WHEN p_requested<0 THEN (v_amount::bigint-p_requested::bigint)::integer ELSE 0 END;
 v_after:=v_balance::bigint+v_amount::bigint;
 IF v_after NOT BETWEEN 0 AND 2147483647 THEN RAISE EXCEPTION 'Points balance outside supported integer range'; END IF;
 v_payload:=jsonb_build_object('transactionId',v_id,'operationKey',p_key,'cause',p_cause,
   'bookingId',p_booking,'referralId',p_referral,'sourceEventId',p_event,'compensatesTransactionId',p_compensates,
   'historicalValue',p_snapshot,'configurationVersionId',p_config,'configurationAsOf',p_config_at,
   'requestedAmount',p_requested,'appliedAmount',v_amount,'unrecoveredAmount',v_shortfall,
   'balanceBefore',v_balance,'balanceAfter',v_after);
 v_audit:=loyalty.write_audit(p_user,'POINTS_TRANSACTION_POSTED',p_reason,v_payload);
 IF v_shortfall>0 THEN
   PERFORM loyalty.write_audit(p_user,'UNRECOVERED_REVERSAL_AMOUNT',p_reason,v_payload);
 END IF;
 v_outbox:=loyalty.write_outbox(p_user,'LOYALTY_POINTS_LEDGER_COMMITTED',v_id,v_payload);
 v_token:=loyalty.enter_write('loyalty.points_transaction',v_id::text);
 INSERT INTO loyalty.points_transaction(id,points_account_id,amount,cause,booking_id,referral_id,granted_value_snapshot,
   compensates_transaction_id,unrecovered_reversal_amount,admin_id,reason,occurred_at,ledger_sequence,operation_key,
   requested_amount,balance_before,balance_after,source_trust_event_id,configuration_as_of,configuration_version_id,
   correlation_id,audit_event_id,outbox_event_id)
 VALUES(v_id,p_user,v_amount,p_cause,p_booking,p_referral,COALESCE(p_snapshot,0),p_compensates,v_shortfall,
   v_ctx.admin_id,p_reason,v_ctx.command_at,nextval('loyalty.ledger_sequence_seq'),p_key,p_requested,v_balance,v_after::integer,
   p_event,p_config_at,p_config,v_ctx.correlation_id,v_audit,v_outbox);
 PERFORM loyalty.leave_write(v_token);
 v_token:=loyalty.enter_write('loyalty.points_account',p_user::text);
 UPDATE loyalty.points_account SET balance=v_after::integer,updated_at=v_ctx.command_at WHERE user_id=p_user;
 PERFORM loyalty.leave_write(v_token);
 RETURN v_id;
END;
$function$;

-- ---------- 8. Paying the Booking Fee: Points first, then the Wallet ----------
-- The automatic rule (PCR-010 item 4), pure: what a balance pays of a fee. PER_BOOKING: a full unit pays the whole
-- fee, fewer Points pay nothing. PER_HOUR: a unit pays an hour, half a unit a half hour; as many half hours as the
-- balance covers, up to the whole booking. A zero fee is paid by nothing.
CREATE OR REPLACE FUNCTION loyalty.fee_points_cover(p_mode text,p_rate numeric,p_minutes integer,p_fee numeric,
 p_balance integer,p_unit integer)
RETURNS TABLE(half_hours_paid integer,points_used integer,amount_paid numeric)
LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $function$
DECLARE k integer; half integer;
BEGIN
 IF p_mode IS NULL OR p_mode NOT IN ('PER_BOOKING','PER_HOUR') OR p_unit IS NULL OR p_unit<2 OR mod(p_unit,2)<>0
    OR p_balance IS NULL OR p_balance<0 OR p_minutes IS NULL OR p_minutes<=0 OR mod(p_minutes,30)<>0
    OR p_fee IS NULL OR p_fee<0 OR p_rate IS NULL OR p_rate<0 THEN
   RAISE EXCEPTION 'Invalid Booking Fee Points cover input';
 END IF;
 IF p_fee=0 THEN RETURN QUERY SELECT NULL::integer,0,0::numeric; RETURN; END IF;
 IF p_mode='PER_BOOKING' THEN
   IF p_balance>=p_unit THEN RETURN QUERY SELECT NULL::integer,p_unit,p_fee;
   ELSE RETURN QUERY SELECT NULL::integer,0,0::numeric; END IF;
   RETURN;
 END IF;
 half:=p_unit/2;
 k:=LEAST(p_minutes/30,p_balance/half);
 IF k=0 THEN RETURN QUERY SELECT NULL::integer,0,0::numeric; RETURN; END IF;
 RETURN QUERY SELECT k,(k::bigint*half)::integer,LEAST(round(p_rate*k/2.0,2),p_fee);
END;
$function$;

-- The money part of a Booking's fee: what Points did not pay. The Wallet or the payment provider collects exactly this.
CREATE OR REPLACE FUNCTION loyalty.fee_money_due(p_booking uuid)
RETURNS numeric LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
 SELECT b.booking_fee_snapshot-COALESCE((SELECT p.amount_paid FROM loyalty.booking_fee_points_payment p WHERE p.booking_id=b.id),0)
   FROM booking.booking b WHERE b.id=p_booking;
$function$;

-- The one producer of a Booking Fee payment (PCR-009, PCR-010). Called by Online Booking creation, in the command that
-- creates the Booking, with the Customer in its Loyalty context. Points pay what the automatic rule allows; then the
-- Wallet pays what is left when it holds all of it; anything still left stays uncollected until a payment provider
-- exists (ADR-018). A second call for the same Booking answers the payment it already made.
CREATE OR REPLACE FUNCTION loyalty.pay_booking_fee(p_booking uuid)
RETURNS TABLE(points_transaction_id uuid,wallet_transaction_id uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE b booking.booking%ROWTYPE; ctx record; fee record; cfg record; cov record; unit integer; pbal integer; bal numeric;
 pay loyalty.booking_fee_points_payment%ROWTYPE; rec loyalty.booking_fee_receipt%ROWTYPE; due numeric;
 ptx uuid; wtx uuid; pid uuid:=gen_random_uuid(); rid uuid:=gen_random_uuid(); wid uuid:=gen_random_uuid();
 raid uuid; waid uuid; oid bigint; tok uuid; py jsonb;
BEGIN
 SELECT * INTO STRICT b FROM booking.booking WHERE id=p_booking;
 IF b.source<>'ONLINE' OR b.customer_id IS NULL THEN RAISE EXCEPTION 'Only an Online customer Booking has a Booking Fee to pay'; END IF;
 SELECT * INTO STRICT ctx FROM loyalty.command_context(b.customer_id);
 SELECT * INTO pay FROM loyalty.booking_fee_points_payment WHERE booking_id=b.id;
 SELECT * INTO rec FROM loyalty.booking_fee_receipt WHERE booking_id=b.id;
 IF pay.id IS NOT NULL OR rec.id IS NOT NULL THEN
   SELECT w.id INTO wtx FROM loyalty.wallet_transaction w WHERE w.receipt_id=rec.id AND w.cause='BOOKING_FEE_PAYMENT';
   RETURN QUERY SELECT pay.points_transaction_id,wtx;
   RETURN;
 END IF;
 IF b.booking_fee_snapshot=0 THEN RETURN QUERY SELECT NULL::uuid,NULL::uuid; RETURN; END IF;
 IF b.status<>'PENDING' OR b.created_at IS DISTINCT FROM ctx.command_at THEN
   RAISE EXCEPTION 'A Booking Fee is paid only by the command that creates the Booking';
 END IF;
 SELECT * INTO STRICT fee FROM config.booking_fee_for(b.nominal_booked_minutes,b.created_at);
 IF fee.fee_amount IS DISTINCT FROM b.booking_fee_snapshot THEN RAISE EXCEPTION 'Booking Fee snapshot differs from its configuration'; END IF;
 SELECT * INTO STRICT cfg FROM loyalty.configuration_at('loyalty.points_per_fee_unit',b.created_at);
 unit:=loyalty.config_integer('loyalty.points_per_fee_unit',b.created_at);
 SELECT a.balance INTO STRICT pbal FROM loyalty.points_account a WHERE a.user_id=b.customer_id FOR UPDATE;
 SELECT * INTO STRICT cov FROM loyalty.fee_points_cover(fee.fee_mode,fee.fee_rate,b.nominal_booked_minutes,b.booking_fee_snapshot,pbal,unit);
 IF cov.points_used>0 THEN
   ptx:=loyalty.post_points(b.customer_id,-cov.points_used,'BOOKING_FEE_PAYMENT','FEE_POINTS_PAYMENT:'||b.id::text,
     NULL,NULL,NULL,NULL,unit,cfg.version_id,b.created_at,'Booking Fee paid with Points');
   tok:=loyalty.enter_write('loyalty.booking_fee_points_payment',pid::text);
   INSERT INTO loyalty.booking_fee_points_payment(id,booking_id,user_id,fee_mode,fee_rate,fee_amount,points_per_fee_unit,
     configuration_version_id,half_hours_paid,points_used,amount_paid,points_transaction_id,paid_at,correlation_id)
   VALUES(pid,b.id,b.customer_id,fee.fee_mode,fee.fee_rate,b.booking_fee_snapshot,unit,cfg.version_id,cov.half_hours_paid,
     cov.points_used,cov.amount_paid,ptx,ctx.command_at,ctx.correlation_id);
   PERFORM loyalty.leave_write(tok);
 END IF;
 due:=b.booking_fee_snapshot-cov.amount_paid;
 IF due>0 THEN
   SELECT a.balance INTO STRICT bal FROM loyalty.wallet_account a WHERE a.user_id=b.customer_id FOR UPDATE;
   -- PCR-009 on the remainder: the Wallet pays all of it or nothing.
   IF bal>=due THEN
     raid:=loyalty.write_audit(b.customer_id,'BOOKING_FEE_COLLECTION_RECORDED','Booking Fee paid from the Wallet',
      jsonb_build_object('receiptId',rid,'bookingId',b.id,'amount',due,'provider','WALLET',
        'providerReference',wid::text,'providerOccurredAt',ctx.command_at));
     tok:=loyalty.enter_write('loyalty.booking_fee_receipt',rid::text);
     INSERT INTO loyalty.booking_fee_receipt(id,booking_id,user_id,amount,currency,provider,provider_reference,provider_occurred_at,received_at,correlation_id,audit_event_id)
     VALUES(rid,b.id,b.customer_id,due,'EGP','WALLET',wid::text,ctx.command_at,ctx.command_at,ctx.correlation_id,raid);
     PERFORM loyalty.leave_write(tok);
     py:=jsonb_build_object('walletTransactionId',wid,'receiptId',rid,'bookingId',b.id,'amount',-due,
       'balanceBefore',bal,'balanceAfter',bal-due);
     waid:=loyalty.write_audit(b.customer_id,'BOOKING_FEE_WALLET_PAYMENT','Booking Fee paid from the Wallet',py);
     oid:=loyalty.write_outbox(b.customer_id,'LOYALTY_WALLET_LEDGER_COMMITTED',wid,py);
     tok:=loyalty.enter_write('loyalty.wallet_transaction',wid::text);
     INSERT INTO loyalty.wallet_transaction(id,wallet_account_id,amount,cause,booking_id,occurred_at,ledger_sequence,operation_key,
      refund_id,receipt_id,balance_before,balance_after,correlation_id,audit_event_id,outbox_event_id)
     VALUES(wid,b.customer_id,-due,'BOOKING_FEE_PAYMENT',b.id,ctx.command_at,nextval('loyalty.ledger_sequence_seq'),
      'FEE_PAYMENT:'||b.id::text,NULL,rid,bal,bal-due,ctx.correlation_id,waid,oid);
     PERFORM loyalty.leave_write(tok);
     tok:=loyalty.enter_write('loyalty.wallet_account',b.customer_id::text);
     UPDATE loyalty.wallet_account SET balance=bal-due,updated_at=ctx.command_at WHERE user_id=b.customer_id;
     PERFORM loyalty.leave_write(tok);
     wtx:=wid;
   END IF;
 END IF;
 RETURN QUERY SELECT ptx,wtx;
END;
$function$;

-- v1.1.40: the PCR-009 producer is now the Wallet part of loyalty.pay_booking_fee, so Points always pay first.
CREATE OR REPLACE FUNCTION loyalty.pay_fee_from_wallet(p_booking uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
BEGIN
 RETURN (SELECT p.wallet_transaction_id FROM loyalty.pay_booking_fee(p_booking) p);
END;
$function$;

-- The quote (PCR-010 item 6): what a booking of p_minutes created at p_at would pay with the Customer's balances now.
-- Reads only and holds nothing: the booking itself uses its own balances at creation.
CREATE OR REPLACE FUNCTION loyalty.preview_fee_payment(p_user uuid,p_minutes integer,p_at timestamptz)
RETURNS TABLE(fee_amount numeric,points_used integer,paid_by_points numeric,paid_by_wallet numeric,left_to_pay numeric)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE fee record; cov record; pbal integer; wbal numeric; due numeric; w numeric:=0;
BEGIN
 SELECT * INTO STRICT fee FROM config.booking_fee_for(p_minutes,p_at);
 SELECT a.balance INTO STRICT pbal FROM loyalty.points_account a WHERE a.user_id=p_user;
 SELECT a.balance INTO STRICT wbal FROM loyalty.wallet_account a WHERE a.user_id=p_user;
 SELECT * INTO STRICT cov FROM loyalty.fee_points_cover(fee.fee_mode,fee.fee_rate,p_minutes,fee.fee_amount,pbal,
   loyalty.config_integer('loyalty.points_per_fee_unit',p_at));
 due:=fee.fee_amount-cov.amount_paid;
 IF due>0 AND wbal>=due THEN w:=due; END IF;
 RETURN QUERY SELECT fee.fee_amount,cov.points_used,cov.amount_paid,w,due-w;
END;
$function$;

-- ---------- 9. Refunds: the Points go back with the decision; the money part settles as before ----------
CREATE OR REPLACE FUNCTION loyalty.record_refund(p_booking uuid,p_history uuid,p_loss_audit uuid DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE bas record; ctx record; f loyalty.booking_fee_refund%ROWTYPE; tok uuid; fid uuid:=gen_random_uuid(); aid uuid; v_assess text;
 pay loyalty.booking_fee_points_payment%ROWTYPE; paid loyalty.points_transaction%ROWTYPE; rtx uuid;
BEGIN
 SELECT * INTO STRICT bas FROM loyalty.refund_basis(p_booking,p_history,p_loss_audit);
 SELECT * INTO STRICT ctx FROM loyalty.command_context(bas.user_id);
 SELECT * INTO f FROM loyalty.booking_fee_refund WHERE booking_id=p_booking;
 IF FOUND THEN
   IF f.booking_history_id IS DISTINCT FROM p_history OR f.service_loss_audit_id IS DISTINCT FROM p_loss_audit
      OR f.amount IS DISTINCT FROM bas.refund_amount THEN RAISE EXCEPTION 'One refund decision per Booking; changed evidence is not a retry'; END IF;
   PERFORM loyalty.settle_refund(f.id);
   RETURN f.id;
 END IF;
 SELECT a.action INTO v_assess FROM audit.audit_event a WHERE a.id=p_loss_audit;
 -- v1.1.40 (PCR-010): a refund gives back exactly the Points that paid part of the fee, with the decision.
 SELECT * INTO pay FROM loyalty.booking_fee_points_payment WHERE booking_id=p_booking;
 IF bas.refund_amount>0 AND pay.id IS NOT NULL THEN
   SELECT * INTO STRICT paid FROM loyalty.points_transaction WHERE id=pay.points_transaction_id;
   rtx:=loyalty.post_points(bas.user_id,pay.points_used,'BOOKING_FEE_REFUND','FEE_POINTS_REFUND:'||p_booking::text,
     NULL,NULL,NULL,pay.points_transaction_id,pay.points_per_fee_unit,pay.configuration_version_id,paid.configuration_as_of,
     'Booking Fee refund returns the Points used');
 END IF;
 aid:=loyalty.write_audit(bas.user_id,'BOOKING_FEE_REFUND_DECIDED','Canonical Booking Fee policy',
   jsonb_build_object('refundId',fid,'bookingId',p_booking,'bookingHistoryId',p_history,'serviceLossAuditId',p_loss_audit,
     'serviceLossAssessment',v_assess,'feeSnapshot',bas.fee_snapshot,'treatment',bas.treatment,'amount',bas.refund_amount,
     'pointsTransactionId',rtx));
 tok:=loyalty.enter_write('loyalty.booking_fee_refund',fid::text);
 INSERT INTO loyalty.booking_fee_refund(id,booking_id,treatment,amount,terminal_reason,occurred_at,user_id,booking_history_id,
   service_loss_audit_id,fee_snapshot,decision_at,correlation_id,audit_event_id,points_transaction_id)
 VALUES(fid,p_booking,bas.treatment,bas.refund_amount,bas.terminal_reason,ctx.command_at,bas.user_id,p_history,p_loss_audit,
   bas.fee_snapshot,bas.decision_at,ctx.correlation_id,aid,rtx);
 PERFORM loyalty.leave_write(tok);
 PERFORM loyalty.settle_refund(fid);
 RETURN fid;
END;
$function$;
CREATE OR REPLACE FUNCTION loyalty.settle_refund(p_refund uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE f loyalty.booking_fee_refund%ROWTYPE; rec loyalty.booking_fee_receipt%ROWTYPE;
 ctx record; bal numeric; wid uuid:=gen_random_uuid(); aid uuid; oid bigint; tok uuid; py jsonb; money numeric;
BEGIN
 SELECT * INTO STRICT f FROM loyalty.booking_fee_refund WHERE id=p_refund;
 SELECT * INTO STRICT ctx FROM loyalty.command_context(f.user_id);
 SELECT * INTO STRICT f FROM loyalty.booking_fee_refund WHERE id=p_refund FOR UPDATE;
 IF f.wallet_transaction_id IS NOT NULL THEN RETURN f.wallet_transaction_id; END IF;
 IF f.amount=0 THEN RETURN NULL; END IF;
 -- v1.1.40 (PCR-010): only the money part settles here; the Points part went back with the decision.
 money:=f.amount-COALESCE((SELECT p.amount_paid FROM loyalty.booking_fee_points_payment p WHERE p.booking_id=f.booking_id),0);
 IF money<=0 THEN RETURN NULL; END IF;
 SELECT * INTO rec FROM loyalty.booking_fee_receipt WHERE booking_id=f.booking_id;
 -- A refund obligation may wait for a verified collection receipt; it is not a fabricated Wallet credit.
 IF NOT FOUND THEN RETURN NULL; END IF;
 -- An already-collected fee remains refundable even if NEW collection is subsequently disabled.
 IF rec.user_id IS DISTINCT FROM f.user_id OR rec.amount<>loyalty.fee_money_due(f.booking_id) OR money>rec.amount THEN RAISE EXCEPTION 'Refund collection proof mismatch'; END IF;
 SELECT a.balance INTO STRICT bal FROM loyalty.wallet_account a WHERE a.user_id=f.user_id FOR UPDATE;
 py:=jsonb_build_object('walletTransactionId',wid,'refundId',f.id,'receiptId',rec.id,'bookingId',f.booking_id,'amount',money,'balanceBefore',bal,'balanceAfter',bal+money);
 aid:=loyalty.write_audit(f.user_id,'BOOKING_FEE_REFUND_CREDITED','Audited Booking Fee refund',py);
 oid:=loyalty.write_outbox(f.user_id,'LOYALTY_WALLET_LEDGER_COMMITTED',wid,py);
 tok:=loyalty.enter_write('loyalty.wallet_transaction',wid::text);
 INSERT INTO loyalty.wallet_transaction(id,wallet_account_id,amount,cause,booking_id,occurred_at,ledger_sequence,operation_key,
  refund_id,receipt_id,balance_before,balance_after,correlation_id,audit_event_id,outbox_event_id)
 VALUES(wid,f.user_id,money,'BOOKING_FEE_REFUND',f.booking_id,ctx.command_at,nextval('loyalty.ledger_sequence_seq'),
  'FEE_REFUND:'||f.id::text,f.id,rec.id,bal,bal+money,ctx.correlation_id,aid,oid);
 PERFORM loyalty.leave_write(tok);
 tok:=loyalty.enter_write('loyalty.wallet_account',f.user_id::text);
 UPDATE loyalty.wallet_account SET balance=bal+money,updated_at=ctx.command_at WHERE user_id=f.user_id;
 PERFORM loyalty.leave_write(tok);
 tok:=loyalty.enter_write('loyalty.booking_fee_refund',f.id::text);
 UPDATE loyalty.booking_fee_refund SET receipt_id=rec.id,wallet_transaction_id=wid,settled_at=ctx.command_at WHERE id=f.id;
 PERFORM loyalty.leave_write(tok);
 RETURN wid;
END;
$function$;
CREATE OR REPLACE FUNCTION loyalty.record_fee_receipt(p_booking uuid,p_amount numeric,p_provider text,p_reference text,p_provider_at timestamptz)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE b booking.booking%ROWTYPE; ctx record; oldr loyalty.booking_fee_receipt%ROWTYPE;
 rid uuid:=gen_random_uuid(); tok uuid; aid uuid; fid uuid;
BEGIN
 SELECT * INTO STRICT b FROM booking.booking WHERE id=p_booking;
 SELECT * INTO STRICT ctx FROM loyalty.command_context(b.customer_id);
 IF b.source<>'ONLINE' OR p_amount IS NULL OR p_amount<>loyalty.fee_money_due(b.id) OR p_amount<=0 OR p_amount='NaN'::numeric
   OR p_amount<>round(p_amount,2) OR p_provider_at IS NULL OR p_provider_at<b.created_at OR p_provider_at>ctx.command_at
   OR NULLIF(btrim(p_provider),'') IS NULL OR NULLIF(btrim(p_reference),'') IS NULL THEN RAISE EXCEPTION 'Invalid verified fee receipt'; END IF;
 SELECT * INTO oldr FROM loyalty.booking_fee_receipt WHERE booking_id=p_booking;
 IF FOUND THEN
  IF oldr.amount IS DISTINCT FROM p_amount OR oldr.provider IS DISTINCT FROM p_provider
     OR oldr.provider_reference IS DISTINCT FROM p_reference OR oldr.provider_occurred_at IS DISTINCT FROM p_provider_at THEN
    RAISE EXCEPTION 'Booking fee receipt is immutable; different payment is not an idempotent retry';
  END IF;
  RETURN oldr.id;
 END IF;
 IF NOT loyalty.wallet_enabled(ctx.command_at) THEN RAISE EXCEPTION 'Payment/refund integration is disabled at Launch'; END IF;
 aid:=loyalty.write_audit(b.customer_id,'BOOKING_FEE_COLLECTION_RECORDED','Verified settlement adapter receipt',
  jsonb_build_object('receiptId',rid,'bookingId',b.id,'amount',p_amount,'provider',p_provider,'providerReference',p_reference,'providerOccurredAt',p_provider_at));
 tok:=loyalty.enter_write('loyalty.booking_fee_receipt',rid::text);
 INSERT INTO loyalty.booking_fee_receipt(id,booking_id,user_id,amount,currency,provider,provider_reference,provider_occurred_at,received_at,correlation_id,audit_event_id)
 VALUES(rid,b.id,b.customer_id,p_amount,'EGP',p_provider,p_reference,p_provider_at,ctx.command_at,ctx.correlation_id,aid);
 PERFORM loyalty.leave_write(tok);
 SELECT id INTO fid FROM loyalty.booking_fee_refund WHERE booking_id=b.id;
 IF FOUND THEN PERFORM loyalty.settle_refund(fid); END IF;
 RETURN rid;
END;
$function$;

-- ---------- 10. loyalty.assert_booking and loyalty.assert_user: the Points-payment bindings ----------
CREATE OR REPLACE FUNCTION loyalty.assert_booking(p_booking uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE b booking.booking%ROWTYPE; bas record; a loyalty.reward_application%ROWTYPE;
 f loyalty.booking_fee_refund%ROWTYPE; fb record; last_h uuid; total_paid bigint;
 pay loyalty.booking_fee_points_payment%ROWTYPE; ptx loyalty.points_transaction%ROWTYPE; fee record; cfg record; cov record;
BEGIN
 SELECT * INTO b FROM booking.booking WHERE id=p_booking;
 IF NOT FOUND OR b.source<>'ONLINE' THEN RETURN; END IF;
 SELECT * INTO bas FROM loyalty.booking_reward_basis(p_booking);
 IF FOUND THEN
   SELECT * INTO a FROM loyalty.reward_application x WHERE x.booking_id=p_booking ORDER BY x.application_sequence DESC LIMIT 1;
   IF NOT FOUND OR a.source_event_id IS DISTINCT FROM bas.current_event_id OR a.user_id IS DISTINCT FROM bas.user_id
      OR a.reward_value IS DISTINCT FROM bas.reward_value OR a.configuration_as_of IS DISTINCT FROM bas.configuration_as_of
      OR a.configuration_version_id IS DISTINCT FROM bas.configuration_version_id
      OR a.entitlement_after IS DISTINCT FROM (CASE WHEN bas.current_type='BOOKING_ATTENDED' THEN bas.reward_value ELSE 0 END) THEN
     RAISE EXCEPTION 'Canonical Booking outcome/correction requires final Loyalty reconciliation in the same transaction';
   END IF;
   SELECT COALESCE(sum(t.amount::bigint),0) INTO total_paid FROM loyalty.points_transaction t WHERE t.booking_id=p_booking;
   IF total_paid<0 OR total_paid>bas.reward_value OR (bas.current_type='BOOKING_ATTENDED' AND total_paid<>bas.reward_value) THEN
     RAISE EXCEPTION 'Booking reward is omitted or duplicated';
   END IF;
 ELSIF EXISTS(SELECT 1 FROM loyalty.points_transaction t WHERE t.booking_id=p_booking) OR EXISTS(SELECT 1 FROM loyalty.reward_application x WHERE x.booking_id=p_booking) THEN
   RAISE EXCEPTION 'Booking reward exists without its canonical source';
 END IF;
 SELECT * INTO pay FROM loyalty.booking_fee_points_payment WHERE booking_id=p_booking;
 -- Every reason-coded terminal Online Booking has an explicit decision, including zero-fee decisions.
 SELECT * INTO f FROM loyalty.booking_fee_refund WHERE booking_id=p_booking;
 IF b.status IN ('CANCELLED','REJECTED') AND f.id IS NULL THEN RAISE EXCEPTION 'Terminal Booking fee decision omitted'; END IF;
 IF f.id IS NOT NULL THEN
   SELECT * INTO STRICT fb FROM loyalty.refund_basis(b.id,f.booking_history_id,f.service_loss_audit_id);
   IF f.user_id IS DISTINCT FROM fb.user_id OR f.treatment IS DISTINCT FROM fb.treatment
      OR f.terminal_reason IS DISTINCT FROM fb.terminal_reason OR f.fee_snapshot IS DISTINCT FROM fb.fee_snapshot
      OR f.amount IS DISTINCT FROM fb.refund_amount OR f.decision_at IS DISTINCT FROM fb.decision_at THEN
     RAISE EXCEPTION 'Stored refund decision differs from the canonical policy';
   END IF;
   IF f.wallet_transaction_id IS NOT NULL AND NOT EXISTS(
      SELECT 1 FROM loyalty.wallet_transaction w JOIN loyalty.booking_fee_receipt rc ON rc.id=w.receipt_id
       WHERE w.id=f.wallet_transaction_id AND w.refund_id=f.id AND w.receipt_id=f.receipt_id
         AND w.wallet_account_id=f.user_id AND w.booking_id=b.id AND w.amount=f.amount-COALESCE(pay.amount_paid,0)
         AND w.occurred_at=f.settled_at AND rc.booking_id=b.id AND rc.user_id=f.user_id
         AND rc.amount=f.fee_snapshot-COALESCE(pay.amount_paid,0)
   ) THEN RAISE EXCEPTION 'Refund settlement marker lacks its exact verified receipt/Wallet credit'; END IF;
   IF f.amount>0 AND EXISTS(SELECT 1 FROM loyalty.booking_fee_receipt rc WHERE rc.booking_id=b.id)
      AND f.wallet_transaction_id IS NULL THEN RAISE EXCEPTION 'Collected eligible fee has an unfulfilled refund obligation'; END IF;
 END IF;
 -- v1.1.40 (PCR-010): what Points paid is the exact automatic payment of the creation, and a refund gives it back.
 IF pay.id IS NOT NULL THEN
   SELECT * INTO STRICT fee FROM config.booking_fee_for(b.nominal_booked_minutes,b.created_at);
   SELECT * INTO STRICT cfg FROM loyalty.configuration_at('loyalty.points_per_fee_unit',b.created_at);
   SELECT * INTO ptx FROM loyalty.points_transaction t WHERE t.id=pay.points_transaction_id;
   SELECT * INTO cov FROM loyalty.fee_points_cover(pay.fee_mode,pay.fee_rate,b.nominal_booked_minutes,pay.fee_amount,
     ptx.balance_before,pay.points_per_fee_unit);
   IF pay.user_id IS DISTINCT FROM b.customer_id OR pay.fee_amount IS DISTINCT FROM b.booking_fee_snapshot
      OR pay.fee_mode IS DISTINCT FROM fee.fee_mode OR pay.fee_rate IS DISTINCT FROM fee.fee_rate
      OR pay.configuration_version_id IS DISTINCT FROM cfg.version_id
      OR pay.points_per_fee_unit IS DISTINCT FROM loyalty.config_integer('loyalty.points_per_fee_unit',b.created_at)
      OR ptx.id IS NULL OR ptx.cause<>'BOOKING_FEE_PAYMENT' OR ptx.points_account_id<>b.customer_id
      OR ptx.amount<>-pay.points_used OR ptx.operation_key<>'FEE_POINTS_PAYMENT:'||b.id::text
      OR ptx.configuration_version_id IS DISTINCT FROM pay.configuration_version_id
      OR ptx.configuration_as_of IS DISTINCT FROM b.created_at OR ptx.occurred_at IS DISTINCT FROM b.created_at
      OR ptx.granted_value_snapshot<>pay.points_per_fee_unit OR pay.paid_at IS DISTINCT FROM b.created_at
      OR cov.points_used IS DISTINCT FROM pay.points_used OR cov.half_hours_paid IS DISTINCT FROM pay.half_hours_paid
      OR cov.amount_paid IS DISTINCT FROM pay.amount_paid THEN
     RAISE EXCEPTION 'Booking Fee Points payment is not the exact automatic payment of its creation';
   END IF;
 END IF;
 IF f.id IS NOT NULL AND (f.points_transaction_id IS NOT NULL) IS DISTINCT FROM (f.amount>0 AND pay.id IS NOT NULL) THEN
   RAISE EXCEPTION 'A refunded fee paid with Points gives the Points back with its decision';
 END IF;
 IF f.points_transaction_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM loyalty.points_transaction t
    WHERE t.id=f.points_transaction_id AND t.cause='BOOKING_FEE_REFUND' AND t.points_account_id=f.user_id
      AND t.amount=pay.points_used AND t.compensates_transaction_id=pay.points_transaction_id
      AND t.operation_key='FEE_POINTS_REFUND:'||b.id::text) THEN
   RAISE EXCEPTION 'Booking Fee Points refund is not the exact Points used';
 END IF;
END;
$function$;
CREATE OR REPLACE FUNCTION loyalty.assert_user(p_user uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE pb integer; wb numeric; ps bigint; ws numeric; rr record; ap record; pt loyalty.points_transaction%ROWTYPE;
BEGIN
 IF p_user IS NULL THEN RETURN; END IF;
 SELECT balance INTO pb FROM loyalty.points_account WHERE user_id=p_user;
 IF NOT FOUND THEN RAISE EXCEPTION 'User is missing Points account'; END IF;
 SELECT balance INTO wb FROM loyalty.wallet_account WHERE user_id=p_user;
 IF NOT FOUND THEN RAISE EXCEPTION 'User is missing Wallet account'; END IF;
 SELECT COALESCE(sum(amount::bigint),0) INTO ps FROM loyalty.points_transaction WHERE points_account_id=p_user;
 SELECT COALESCE(sum(amount),0) INTO ws FROM loyalty.wallet_transaction WHERE wallet_account_id=p_user;
 IF pb IS DISTINCT FROM ps OR wb IS DISTINCT FROM ws THEN RAISE EXCEPTION 'Loyalty account balances must equal physical ledger sums'; END IF;
 IF EXISTS(SELECT 1 FROM (
    SELECT t.*,sum(amount::bigint) OVER(ORDER BY ledger_sequence ROWS UNBOUNDED PRECEDING) run
    FROM loyalty.points_transaction t WHERE t.points_account_id=p_user
  ) q WHERE q.balance_after IS DISTINCT FROM q.run OR q.balance_before::bigint IS DISTINCT FROM q.run-q.amount) THEN
   RAISE EXCEPTION 'Points ledger before/after chain mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM (
    SELECT t.*,sum(amount) OVER(ORDER BY ledger_sequence ROWS UNBOUNDED PRECEDING) run
    FROM loyalty.wallet_transaction t WHERE t.wallet_account_id=p_user
  ) q WHERE q.balance_after IS DISTINCT FROM q.run OR q.balance_before IS DISTINCT FROM q.run-q.amount) THEN
   RAISE EXCEPTION 'Wallet ledger before/after chain mismatch'; END IF;
 FOR pt IN SELECT * FROM loyalty.points_transaction WHERE points_account_id=p_user LOOP
   IF NOT EXISTS(SELECT 1 FROM audit.audit_event a WHERE a.id=pt.audit_event_id AND a.subject_type='USER'
     AND a.subject_id=p_user AND a.action='POINTS_TRANSACTION_POSTED' AND a.occurred_at=pt.occurred_at
     AND a.correlation_id=pt.correlation_id AND a.payload->>'transactionId'=pt.id::text
     AND a.payload->>'appliedAmount'=pt.amount::text AND a.payload->>'requestedAmount'=pt.requested_amount::text
     AND a.payload->>'unrecoveredAmount'=pt.unrecovered_reversal_amount::text
     AND ((pt.admin_id IS NULL AND a.actor_type='SYSTEM' AND a.actor_id IS NULL) OR
          (pt.admin_id IS NOT NULL AND a.actor_type='ADMIN' AND a.actor_id=pt.admin_id AND NULLIF(btrim(a.reason),'') IS NOT NULL))) THEN
     RAISE EXCEPTION 'Points ledger audit is not exact'; END IF;
   IF NOT EXISTS(SELECT 1 FROM technical.outbox_event o WHERE o.id=pt.outbox_event_id
     AND o.event_type='LOYALTY_POINTS_LEDGER_COMMITTED' AND o.aggregate_type='LOYALTY' AND o.aggregate_id=pt.id
     AND o.correlation_id=pt.correlation_id AND o.payload->>'recipientUserId'=p_user::text
     AND o.payload->>'appliedAmount'=pt.amount::text) THEN RAISE EXCEPTION 'Points ledger notification envelope missing'; END IF;
   IF pt.cause NOT IN ('ADMIN','BOOKING_FEE_PAYMENT','BOOKING_FEE_REFUND') AND NOT EXISTS(SELECT 1 FROM loyalty.reward_application a WHERE a.points_transaction_id=pt.id
      AND a.user_id=p_user AND a.booking_id IS NOT DISTINCT FROM pt.booking_id AND a.referral_id IS NOT DISTINCT FROM pt.referral_id
      AND a.source_event_id IS NOT DISTINCT FROM pt.source_trust_event_id) THEN
     RAISE EXCEPTION 'Reward movement has no exact source application'; END IF;
   -- v1.1.40 (PCR-010): a Booking Fee Points movement is bound to its payment, or to the refund that gave it back.
   IF pt.cause='BOOKING_FEE_PAYMENT' AND NOT EXISTS(SELECT 1 FROM loyalty.booking_fee_points_payment p
      WHERE p.points_transaction_id=pt.id AND p.user_id=p_user AND p.points_used=-pt.amount
        AND pt.operation_key='FEE_POINTS_PAYMENT:'||p.booking_id::text) THEN
     RAISE EXCEPTION 'Booking Fee Points payment has no payment record'; END IF;
   IF pt.cause='BOOKING_FEE_REFUND' AND NOT EXISTS(SELECT 1 FROM loyalty.booking_fee_refund f
      JOIN loyalty.booking_fee_points_payment p ON p.booking_id=f.booking_id
      WHERE f.points_transaction_id=pt.id AND f.user_id=p_user AND p.points_transaction_id=pt.compensates_transaction_id
        AND p.points_used=pt.amount AND pt.operation_key='FEE_POINTS_REFUND:'||f.booking_id::text) THEN
     RAISE EXCEPTION 'Booking Fee Points refund has no refund decision'; END IF;
   IF pt.unrecovered_reversal_amount>0 AND NOT EXISTS(SELECT 1 FROM audit.audit_event a WHERE a.action='UNRECOVERED_REVERSAL_AMOUNT'
      AND a.subject_type='USER' AND a.subject_id=p_user AND a.correlation_id=pt.correlation_id
      AND a.payload->>'transactionId'=pt.id::text AND a.payload->>'unrecoveredAmount'=pt.unrecovered_reversal_amount::text) THEN
      RAISE EXCEPTION 'Unrecovered amount audit omitted'; END IF;
 END LOOP;
 FOR rr IN SELECT w.* FROM loyalty.wallet_transaction w WHERE w.wallet_account_id=p_user LOOP
   -- v1.1.39 (PCR-009): a Wallet payment of a Booking Fee is bound to its WALLET receipt, its audit and its event.
   IF rr.cause='BOOKING_FEE_PAYMENT' THEN
     IF NOT EXISTS(SELECT 1 FROM loyalty.booking_fee_receipt rc JOIN booking.booking b ON b.id=rc.booking_id
       WHERE rc.id=rr.receipt_id AND rc.booking_id=rr.booking_id AND rc.user_id=p_user AND rc.provider='WALLET'
         AND rc.provider_reference=rr.id::text AND rc.amount=-rr.amount AND b.customer_id=p_user
         AND b.source='ONLINE' AND loyalty.fee_money_due(b.id)=rc.amount) THEN
       RAISE EXCEPTION 'Wallet payment is not bound to its Booking Fee receipt'; END IF;
     IF NOT EXISTS(SELECT 1 FROM audit.audit_event a WHERE a.id=rr.audit_event_id AND a.action='BOOKING_FEE_WALLET_PAYMENT'
        AND a.subject_type='USER' AND a.subject_id=p_user AND a.payload->>'walletTransactionId'=rr.id::text
        AND a.correlation_id=rr.correlation_id AND a.occurred_at=rr.occurred_at) THEN RAISE EXCEPTION 'Wallet payment audit omitted'; END IF;
     IF NOT EXISTS(SELECT 1 FROM technical.outbox_event o WHERE o.id=rr.outbox_event_id AND o.aggregate_type='LOYALTY'
        AND o.aggregate_id=rr.id AND o.event_type='LOYALTY_WALLET_LEDGER_COMMITTED'
        AND o.payload->>'recipientUserId'=p_user::text AND o.correlation_id=rr.correlation_id) THEN RAISE EXCEPTION 'Wallet outbox omitted'; END IF;
     CONTINUE;
   END IF;
   IF NOT EXISTS(SELECT 1 FROM loyalty.booking_fee_refund f JOIN loyalty.booking_fee_receipt rc ON rc.id=rr.receipt_id
     WHERE f.id=rr.refund_id AND f.wallet_transaction_id=rr.id AND f.booking_id=rr.booking_id AND f.user_id=p_user
       AND rr.amount=f.amount-COALESCE((SELECT p.amount_paid FROM loyalty.booking_fee_points_payment p WHERE p.booking_id=f.booking_id),0)
       AND rc.booking_id=rr.booking_id AND rc.user_id=p_user AND rc.amount>=rr.amount) THEN
     RAISE EXCEPTION 'Wallet credit is not bound to collected eligible fee'; END IF;
   IF NOT EXISTS(SELECT 1 FROM audit.audit_event a WHERE a.id=rr.audit_event_id AND a.action='BOOKING_FEE_REFUND_CREDITED'
      AND a.subject_type='USER' AND a.subject_id=p_user AND a.payload->>'walletTransactionId'=rr.id::text
      AND a.correlation_id=rr.correlation_id AND a.occurred_at=rr.occurred_at) THEN RAISE EXCEPTION 'Wallet audit omitted'; END IF;
   IF NOT EXISTS(SELECT 1 FROM technical.outbox_event o WHERE o.id=rr.outbox_event_id AND o.aggregate_type='LOYALTY'
      AND o.aggregate_id=rr.id AND o.event_type='LOYALTY_WALLET_LEDGER_COMMITTED'
      AND o.payload->>'recipientUserId'=p_user::text AND o.correlation_id=rr.correlation_id) THEN RAISE EXCEPTION 'Wallet outbox omitted'; END IF;
 END LOOP;
 FOR ap IN SELECT a.*,lag(a.id) OVER(PARTITION BY source_kind,booking_id,referral_id ORDER BY application_sequence) prev_id,
   lag(a.entitlement_after,1,0) OVER(PARTITION BY source_kind,booking_id,referral_id ORDER BY application_sequence) prev_ent
   FROM loyalty.reward_application a WHERE a.user_id=p_user LOOP
   IF ap.previous_application_id IS DISTINCT FROM ap.prev_id OR ap.entitlement_before IS DISTINCT FROM ap.prev_ent THEN RAISE EXCEPTION 'Reward application chain is branched or discontinuous'; END IF;
 END LOOP;
 PERFORM loyalty.assert_application_history(p_user);
 FOR rr IN SELECT id FROM booking.booking WHERE customer_id=p_user LOOP PERFORM loyalty.assert_booking(rr.id); END LOOP;
 FOR rr IN SELECT id FROM loyalty.referral WHERE referred_user_id=p_user OR referrer_user_id=p_user LOOP PERFORM loyalty.assert_referral(rr.id); END LOOP;
 IF EXISTS(SELECT 1 FROM iam.user_account u WHERE u.id=p_user AND NULLIF(btrim(u.referred_by_code),'') IS NOT NULL
   AND NOT EXISTS(SELECT 1 FROM loyalty.referral r WHERE r.referred_user_id=u.id AND r.code=btrim(u.referred_by_code))) THEN
    RAISE EXCEPTION 'Referral affiliation requires its source record'; END IF;
END;
$function$;
