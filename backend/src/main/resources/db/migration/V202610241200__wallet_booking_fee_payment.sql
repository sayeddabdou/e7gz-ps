-- =====================================================================================================
-- E7-66 PR 3 / physical model v1.1.39 — the Wallet pays a future Booking's Booking Fee (PCR-009, Product Owner
-- decision 24 Oct 2026; amends PCR-004 "no payment with the wallet"). A refund of a collected fee is already credited
-- to the in-app Wallet, never back to the card (FR-PTS-10). Now, when a new Online Booking's stored fee is above zero
-- and the Wallet holds at least that amount, the command that creates the Booking pays the whole fee from the Wallet.
-- The money never leaves E7GZ: no withdrawal, no top-up, no partial payment, never the venue gaming charge.
--
--   1. loyalty.wallet_transaction: a BOOKING_FEE_PAYMENT debit beside the BOOKING_FEE_REFUND credit
--      (ck_wallet_tx_launch_source becomes ck_wallet_tx_source); refund_id is set on a credit only.
--   2. loyalty.pay_fee_from_wallet(booking): the one producer of a Wallet payment.
--   3. loyalty.assert_user: its V1 definition plus the Wallet-payment binding.
--   4. loyalty.guard_audit_origin: its V1 definition plus BOOKING_FEE_WALLET_PAYMENT as a private-producer action.
--
-- The Wallet payment is the Booking's collection receipt (provider WALLET), so a later refund of that fee settles back
-- into the Wallet through loyalty.settle_refund, unchanged. Grants: the security baseline grants EXECUTE on the new
-- producer to both runtime roles after this migration. Every existing Wallet row is a refund credit with its refund_id,
-- so it satisfies the new check; no backfill.
-- =====================================================================================================

ALTER TABLE loyalty.wallet_transaction ALTER COLUMN refund_id DROP NOT NULL;
ALTER TABLE loyalty.wallet_transaction DROP CONSTRAINT ck_wallet_tx_launch_source;
ALTER TABLE loyalty.wallet_transaction ADD CONSTRAINT ck_wallet_tx_source CHECK (
    amount<>'NaN'::numeric AND balance_before<>'NaN'::numeric AND balance_after=balance_before+amount
    AND ((cause='BOOKING_FEE_REFUND' AND amount>0 AND refund_id IS NOT NULL)
      OR (cause='BOOKING_FEE_PAYMENT' AND amount<0 AND refund_id IS NULL AND balance_after>=0)));
COMMENT ON COLUMN loyalty.wallet_transaction.refund_id IS E'Set on a BOOKING_FEE_REFUND credit; NULL on a BOOKING_FEE_PAYMENT debit (v1.1.39).';

-- The one producer of a Wallet payment (PCR-009). Called by Online Booking creation, in the command that creates the
-- Booking, with the Customer in its Loyalty context. Pays the whole stored fee when the Wallet holds at least that
-- amount and returns the Wallet transaction; returns NULL when there is nothing to pay (FREE) or the Wallet does not
-- cover it, and the fee then stays uncollected until a payment provider exists (ADR-018). A second call for the same
-- Booking answers the payment it already made.
CREATE OR REPLACE FUNCTION loyalty.pay_fee_from_wallet(p_booking uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE b booking.booking%ROWTYPE; ctx record; rec loyalty.booking_fee_receipt%ROWTYPE; bal numeric;
 rid uuid:=gen_random_uuid(); wid uuid:=gen_random_uuid(); raid uuid; waid uuid; oid bigint; tok uuid; py jsonb;
BEGIN
 SELECT * INTO STRICT b FROM booking.booking WHERE id=p_booking;
 IF b.source<>'ONLINE' OR b.customer_id IS NULL THEN RAISE EXCEPTION 'Only an Online customer Booking has a Booking Fee to pay'; END IF;
 SELECT * INTO STRICT ctx FROM loyalty.command_context(b.customer_id);
 SELECT * INTO rec FROM loyalty.booking_fee_receipt WHERE booking_id=b.id;
 IF FOUND THEN
   SELECT w.id INTO waid FROM loyalty.wallet_transaction w WHERE w.receipt_id=rec.id AND w.cause='BOOKING_FEE_PAYMENT';
   RETURN waid;
 END IF;
 IF b.booking_fee_snapshot=0 THEN RETURN NULL; END IF;
 IF b.status<>'PENDING' OR b.created_at IS DISTINCT FROM ctx.command_at THEN
   RAISE EXCEPTION 'A Booking Fee is paid from the Wallet only by the command that creates the Booking';
 END IF;
 SELECT a.balance INTO STRICT bal FROM loyalty.wallet_account a WHERE a.user_id=b.customer_id FOR UPDATE;
 IF bal<b.booking_fee_snapshot THEN RETURN NULL; END IF;
 raid:=loyalty.write_audit(b.customer_id,'BOOKING_FEE_COLLECTION_RECORDED','Booking Fee paid from the Wallet',
  jsonb_build_object('receiptId',rid,'bookingId',b.id,'amount',b.booking_fee_snapshot,'provider','WALLET',
    'providerReference',wid::text,'providerOccurredAt',ctx.command_at));
 tok:=loyalty.enter_write('loyalty.booking_fee_receipt',rid::text);
 INSERT INTO loyalty.booking_fee_receipt(id,booking_id,user_id,amount,currency,provider,provider_reference,provider_occurred_at,received_at,correlation_id,audit_event_id)
 VALUES(rid,b.id,b.customer_id,b.booking_fee_snapshot,'EGP','WALLET',wid::text,ctx.command_at,ctx.command_at,ctx.correlation_id,raid);
 PERFORM loyalty.leave_write(tok);
 py:=jsonb_build_object('walletTransactionId',wid,'receiptId',rid,'bookingId',b.id,'amount',-b.booking_fee_snapshot,
   'balanceBefore',bal,'balanceAfter',bal-b.booking_fee_snapshot);
 waid:=loyalty.write_audit(b.customer_id,'BOOKING_FEE_WALLET_PAYMENT','Booking Fee paid from the Wallet',py);
 oid:=loyalty.write_outbox(b.customer_id,'LOYALTY_WALLET_LEDGER_COMMITTED',wid,py);
 tok:=loyalty.enter_write('loyalty.wallet_transaction',wid::text);
 INSERT INTO loyalty.wallet_transaction(id,wallet_account_id,amount,cause,booking_id,occurred_at,ledger_sequence,operation_key,
  refund_id,receipt_id,balance_before,balance_after,correlation_id,audit_event_id,outbox_event_id)
 VALUES(wid,b.customer_id,-b.booking_fee_snapshot,'BOOKING_FEE_PAYMENT',b.id,ctx.command_at,nextval('loyalty.ledger_sequence_seq'),
  'FEE_PAYMENT:'||b.id::text,NULL,rid,bal,bal-b.booking_fee_snapshot,ctx.correlation_id,waid,oid);
 PERFORM loyalty.leave_write(tok);
 tok:=loyalty.enter_write('loyalty.wallet_account',b.customer_id::text);
 UPDATE loyalty.wallet_account SET balance=bal-b.booking_fee_snapshot,updated_at=ctx.command_at WHERE user_id=b.customer_id;
 PERFORM loyalty.leave_write(tok);
 RETURN wid;
END;
$function$;

-- v1.1.39: assert_user, its V1 definition plus the Wallet-payment binding.
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
   IF pt.cause<>'ADMIN' AND NOT EXISTS(SELECT 1 FROM loyalty.reward_application a WHERE a.points_transaction_id=pt.id
      AND a.user_id=p_user AND a.booking_id IS NOT DISTINCT FROM pt.booking_id AND a.referral_id IS NOT DISTINCT FROM pt.referral_id
      AND a.source_event_id IS NOT DISTINCT FROM pt.source_trust_event_id) THEN
     RAISE EXCEPTION 'Reward movement has no exact source application'; END IF;
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
         AND b.source='ONLINE' AND b.booking_fee_snapshot=rc.amount) THEN
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
       AND f.amount=rr.amount AND rc.booking_id=rr.booking_id AND rc.user_id=p_user AND rc.amount>=rr.amount) THEN
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

-- v1.1.39: guard_audit_origin, its V1 definition plus BOOKING_FEE_WALLET_PAYMENT.
CREATE OR REPLACE FUNCTION loyalty.guard_audit_origin()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
BEGIN
 IF NEW.action IN ('POINTS_TRANSACTION_POSTED','UNRECOVERED_REVERSAL_AMOUNT','REFERRAL_INVALIDATED',
   'BOOKING_SERVICE_LOSS_CONFIRMED','BOOKING_SERVICE_LOSS_NOT_ESTABLISHED','BOOKING_FEE_REFUND_DECIDED','BOOKING_FEE_REFUND_CREDITED',
   'BOOKING_FEE_COLLECTION_RECORDED','LOYALTY_CONFIGURATION_CHANGED') THEN
   PERFORM loyalty.require_write('audit.audit_event',NEW.id::text);
 END IF;
 -- v1.1.39 (PCR-009): the Wallet payment audit is private-producer only too.
 IF NEW.action='BOOKING_FEE_WALLET_PAYMENT' THEN
   PERFORM loyalty.require_write('audit.audit_event',NEW.id::text);
 END IF;
 RETURN NEW;
END;
$function$;
