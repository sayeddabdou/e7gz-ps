#!/bin/bash
# Behaviour tests for v1.1.27 fixes on a freshly exported database.
DB=${1:-t127}
P="psql -h /tmp -p 5499 -U postgres -d $DB -v ON_ERROR_STOP=0 -At"
pass=0; fail=0
ok(){ echo "PASS  $1"; pass=$((pass+1)); }
ko(){ echo "FAIL  $1  :: $2"; fail=$((fail+1)); }
expect_ok(){ out=$($P -c "$2" 2>&1); if echo "$out" | grep -q "ERROR"; then ko "$1" "$(echo "$out"|grep ERROR|head -1)"; else ok "$1"; fi; }
expect_err(){ out=$($P -c "$2" 2>&1); if echo "$out" | grep -q "ERROR"; then if [ -n "$3" ] && ! echo "$out" | grep -q "$3"; then ko "$1" "wrong error: $(echo "$out"|grep ERROR|head -1)"; else ok "$1"; fi; else ko "$1" "expected an error"; fi; }
val(){ $P -c "$1" 2>&1 | tail -1; }

U1=11111111-1111-1111-1111-111111111111; U2=22222222-2222-2222-2222-222222222222; U3=33333333-3333-3333-3333-333333333333
A1=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa; AR=a0000000-0000-0000-0000-000000000001; AR2=a0000000-0000-0000-0000-000000000002
V1=b1111111-1111-1111-1111-111111111111; V2=b2222222-2222-2222-2222-222222222222

# ---------- seed ----------
$P -q -c "INSERT INTO iam.user_account(id,full_name,username,phone_e164,email,password_hash,phone_verified,phone_verified_at,email_verified,referral_code,joined_at) VALUES
 ('$U1','Ahmed','ahmed','+201000000001','a@x.com','h',true,now(),false,'R1',now()),
 ('$U2','Mona','mona','+201000000002','m@x.com','h',true,now(),false,'R2',now()),
 ('$U3','Omar','omar','+201000000003','o@x.com','h',true,now(),false,'R3',now());
 INSERT INTO iam.admin_account(id,email,password_hash,full_name,is_active) VALUES('$A1','admin@x.com','h','Admin',true);
 INSERT INTO geo.area(id,name,is_active) VALUES('$AR','New Cairo',true),('$AR2','Maadi',true);
 INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES
 ('$V1','$AR','V1','addr',ST_GeogFromText('POINT(31.4 30.0)'),'ACTIVE','Africa/Cairo','+20200000001'),
 ('$V2','$AR2','V2','addr',ST_GeogFromText('POINT(31.3 29.9)'),'INACTIVE','Africa/Cairo','+20200000002');" 2>&1 | grep ERROR

# F01
[ "$(val "select count(*) from iam.user_commitment_guard where user_id in ('$U1','$U2','$U3')")" = "3" ] && ok "F01 commitment guard auto-created for new Users" || ko "F01" "guard rows missing"

# F07
[ "$(val "select config.effective_numeric('booking.fee',now())")" = "0" ] && ok "F07 booking.fee effective value = 0" || ko F07 booking
[ "$(val "select config.effective_numeric('tournament.creation_fee',now())")" = "0" ] && ok "F07 tournament.creation_fee effective value = 0" || ko F07 tournament
expect_err "F07 fee cannot be changed without Admin command" "UPDATE config.system_configuration SET value='5' WHERE key='booking.fee'" "private producer"

# F08 username
expect_ok  "F08 first username change allowed" "UPDATE iam.user_account SET username='ahmed2' WHERE id='$U1'"
[ "$(val "select count(*) from iam.username_change where user_id='$U1'")" = "1" ] && ok "F08 username history recorded automatically" || ko F08 history
expect_err "F08 second change inside 30 days rejected" "UPDATE iam.user_account SET username='ahmed3' WHERE id='$U1'" "cooldown"
expect_err "F08 username history is producer-only" "INSERT INTO iam.username_change(id,user_id,old_username,new_username,changed_at) VALUES(gen_random_uuid(),'$U2','a','b',now())" "private producer"
expect_err "F08 username history append-only" "DELETE FROM iam.username_change" "append-only"

# F10 OTP
D=+201099999999
expect_ok  "F10 OTP send" "INSERT INTO iam.otp_challenge(id,user_id,purpose,destination,code_hash,issued_at,expires_at,attempts) VALUES(gen_random_uuid(),'$U3','PHONE_VERIFICATION','$D','c',now(),now()+interval '5 minutes',0)"
expect_err "F10 resend inside 60 s rejected" "INSERT INTO iam.otp_challenge(id,user_id,purpose,destination,code_hash,issued_at,expires_at,attempts) VALUES(gen_random_uuid(),'$U3','PHONE_VERIFICATION','$D','c',now(),now()+interval '5 minutes',0)" "60 seconds"
expect_err "F10 validity must be 5 minutes" "INSERT INTO iam.otp_challenge(id,user_id,purpose,destination,code_hash,issued_at,expires_at,attempts) VALUES(gen_random_uuid(),'$U3','PASSWORD_RESET','+2011','c',now(),now()+interval '10 minutes',0)" "ck_otp_validity"
D2=+201088888888
for m in 50 40 30 20 10; do $P -q -c "INSERT INTO iam.otp_challenge(id,user_id,purpose,destination,code_hash,issued_at,expires_at,attempts) VALUES(gen_random_uuid(),'$U3','PHONE_VERIFICATION','$D2','c',now()-interval '$m minutes',now()-interval '$m minutes'+interval '5 minutes',0)" 2>&1 | grep ERROR; done
[ "$(val "select count(*) from iam.otp_challenge where destination='$D2' and superseded_at is not null")" = "4" ] && ok "F10 new OTP supersedes the previous OTP" || ko F10 supersede
expect_err "F10 sixth send in one hour rejected" "INSERT INTO iam.otp_challenge(id,user_id,purpose,destination,code_hash,issued_at,expires_at,attempts) VALUES(gen_random_uuid(),'$U3','PHONE_VERIFICATION','$D2','c',now(),now()+interval '5 minutes',0)" "5 OTP sends per hour"
OID=$(val "select id from iam.otp_challenge where destination='$D' limit 1")
for i in 1 2 3 4 5; do $P -q -c "UPDATE iam.otp_challenge SET attempts=attempts+1 WHERE id='$OID'" 2>&1 | grep ERROR; done
expect_err "F10 sixth attempt rejected" "UPDATE iam.otp_challenge SET attempts=attempts+1 WHERE id='$OID'"
expect_err "F10 consume after 5 attempts rejected" "UPDATE iam.otp_challenge SET consumed_at=now() WHERE id='$OID'" "no longer usable"
expect_err "F10 expired OTP cannot be used" "UPDATE iam.otp_challenge SET consumed_at=now() WHERE destination='$D2' AND superseded_at IS NULL" "no longer usable"
expect_err "F10 OTP rows are retained" "DELETE FROM iam.otp_challenge WHERE id='$OID'" "retained"

# F09 Areas
expect_err "F09 Area with ACTIVE Venue cannot be deactivated" "UPDATE geo.area SET is_active=false WHERE id='$AR'" "cannot be deactivated"
expect_ok  "F09 Area with only INACTIVE Venues can be deactivated" "UPDATE geo.area SET is_active=false WHERE id='$AR2'"
expect_err "F09 Venue in inactive Area cannot become ACTIVE" "UPDATE venue.venue SET marketplace_status='ACTIVE' WHERE id='$V2'" "inactive Area"
expect_err "F09 new Venue cannot be assigned to inactive Area" "INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES(gen_random_uuid(),'$AR2','V3','a',ST_GeogFromText('POINT(31 30)'),'ONBOARDING','Africa/Cairo','+2')" "ACTIVE Area"
expect_err "F09 Areas are never deleted" "DELETE FROM geo.area WHERE id='$AR2'" "never deleted"

# F12 Owner Password secret
VA=c1111111-1111-1111-1111-111111111111
$P -q -c "INSERT INTO iam.venue_account(id,venue_id,login_identifier,password_hash,account_status,language_preference) VALUES('$VA','$V1','v1','h','ENABLED','en'); INSERT INTO iam.venue_owner_secret(venue_account_id,owner_password_hash,changed_at,version) VALUES('$VA','h1',now()-interval '1 day',0)" 2>&1 | grep ERROR
expect_err "F12 Owner Password change must advance version" "UPDATE iam.venue_owner_secret SET owner_password_hash='h2' WHERE venue_account_id='$VA'" "advance"
expect_ok  "F12 Owner Password change with new changed_at + version" "UPDATE iam.venue_owner_secret SET owner_password_hash='h2',changed_at=now(),version=1 WHERE venue_account_id='$VA'"
expect_err "F12 Owner Password secret never deleted" "DELETE FROM iam.venue_owner_secret" "never deleted"

# F04 / F06 reviews, reports and O19 anonymisation
C=$(cat /proc/sys/kernel/random/uuid)
R1=$($P -c "BEGIN; SELECT review.create_review('$U1','$V1',5::smallint,'great',gen_random_uuid()); COMMIT;" 2>&1 | grep -E '^[0-9a-f-]{36}$')
[ -n "$R1" ] && ok "setup: Review created" || ko "setup review" "$($P -c "BEGIN; SELECT review.create_review('$U1','$V1',5::smallint,'great',gen_random_uuid()); COMMIT;" 2>&1 | grep ERROR)"
expect_ok  "F04 first report accepted" "BEGIN; SELECT review.report_review('$R1','$U2','abuse',NULL,gen_random_uuid()); COMMIT;"
expect_err "F04 duplicate unresolved report by same User rejected" "BEGIN; SELECT review.report_review('$R1','$U2','abuse again',NULL,gen_random_uuid()); COMMIT;" "unresolved report"
expect_ok  "F04 another User may report the same Review" "BEGIN; SELECT review.report_review('$R1','$U3','spam',NULL,gen_random_uuid()); COMMIT;"
expect_ok  "setup: deletion request" "BEGIN; SELECT iam.request_account_deletion('$U1',gen_random_uuid()); COMMIT;"
expect_err "F06 anonymised_at cannot be set directly" "UPDATE iam.user_account SET anonymised_at=now() WHERE id='$U1'" "anonymise_user"
expect_err "F06 anonymisation record is producer-only" "INSERT INTO iam.account_anonymisation(id,user_id,admin_id,reason,performed_at) VALUES(gen_random_uuid(),'$U1','$A1','x',now())" "private producer"
expect_ok  "F06 O19 anonymise_user completes deletion" "BEGIN; SELECT admin.begin_command('$A1',gen_random_uuid(),ARRAY['$U1']::uuid[],ARRAY['$V1']::uuid[]); SELECT iam.anonymise_user('$U1','Customer requested deletion'); COMMIT;"
[ "$(val "select status||','||(author_deleted_at is not null) from review.review where id='$R1'")" = "REMOVED,true" ] && ok "F06 deleted-account Review removed from public display" || ko F06 review "$(val "select status from review.review where id='$R1'")"
[ "$(val "select coalesce(review_count,0) from review.venue_rating_stats where venue_id='$V1'")" = "0" ] && ok "F06 Venue rating no longer counts the Review" || ko F06 rating
[ "$(val "select count(*) from iam.account_deletion_request where user_id='$U1' and completed_at is not null and anonymisation_id is not null")" = "1" ] && ok "F06 deletion request completed and linked" || ko F06 request
[ "$(val "select full_name||'|'||email||'|'||phone_e164 from iam.user_account where id='$U1'")" = "||anonymised:$U1" ] && ok "F06 PII blanked (phone tombstoned)" || ko F06 pii
expect_err "F06 anonymised account immutable" "UPDATE iam.user_account SET full_name='x' WHERE id='$U1'" "immutable"
expect_err "F06 anonymised account cannot request deletion again" "BEGIN; SELECT iam.request_account_deletion('$U1',gen_random_uuid()); COMMIT;" "Active Customer"
[ "$(val "select count(*) from audit.audit_event where action in ('ACCOUNT_ANONYMISED','REVIEW_REMOVED_ACCOUNT_DELETED','ACCOUNT_DELETION_REQUEST_COMPLETED')")" = "3" ] && ok "F06 audit trail written" || ko F06 audit
# new review by U2 then author anonymised -> not reportable (defensive path)
R2=$($P -c "BEGIN; SELECT review.create_review('$U2','$V1',4::smallint,'ok',gen_random_uuid()); COMMIT;" 2>&1 | grep -E '^[0-9a-f-]{36}$')
expect_ok  "F06 anonymising a User with an open report and review" "BEGIN; SELECT admin.begin_command('$A1',gen_random_uuid(),ARRAY['$U2']::uuid[],ARRAY['$V1']::uuid[]); SELECT iam.anonymise_user('$U2','Support request'); COMMIT;"
expect_err "F04 Review of deleted account not reportable" "BEGIN; SELECT review.report_review('$R2','$U3','x',NULL,gen_random_uuid()); COMMIT;" "ACTIVE public Review"

# F03 PUBLIC cannot execute definer functions
$P -q -c "DROP ROLE IF EXISTS e7gz_probe; CREATE ROLE e7gz_probe LOGIN; GRANT USAGE ON SCHEMA iam, review, tournament TO e7gz_probe;" 2>&1 | grep ERROR
out=$(psql -h /tmp -p 5499 -U e7gz_probe -d $DB -At -c "SELECT iam.request_account_deletion('$U3',gen_random_uuid())" 2>&1)
echo "$out" | grep -q "permission denied" && ok "F03 PUBLIC cannot call iam.request_account_deletion" || ko F03 "$out"
out=$(psql -h /tmp -p 5499 -U e7gz_probe -d $DB -At -c "SELECT review.report_review(gen_random_uuid(),'$U3','x',NULL,gen_random_uuid())" 2>&1)
echo "$out" | grep -q "permission denied" && ok "F03 PUBLIC cannot call review.report_review" || ko F03 "$out"

# F11 Online Booking creation predicate (trigger exercised on a structural copy of booking.booking)
TPL=d1111111-1111-1111-1111-111111111111; OS=e1111111-1111-1111-1111-111111111111
$P -q -c "INSERT INTO venue.venue_opening_template(id,venue_id,default_local_open,default_local_close,default_full_day,version,updated_at) VALUES('$TPL','$V1','10:00','04:00',false,0,now());
 INSERT INTO venue.operating_session(id,venue_id,opening_template_id,operating_day,local_start,local_end,session_period,timezone,generated_from_version,generated_at)
 VALUES('$OS','$V1','$TPL',(now() AT TIME ZONE 'Africa/Cairo')::date,(now() AT TIME ZONE 'Africa/Cairo')-interval '2 hours',(now() AT TIME ZONE 'Africa/Cairo')+interval '6 hours',tstzrange(now()-interval '2 hours',now()+interval '6 hours','[)'),'Africa/Cairo',0,now());
 DROP TABLE IF EXISTS public.bk_probe; CREATE TABLE public.bk_probe (LIKE booking.booking);
 CREATE TRIGGER t BEFORE INSERT ON public.bk_probe FOR EACH ROW EXECUTE FUNCTION booking.validate_online_booking_intake();
 DO \$d\$ DECLARE c text; BEGIN FOR c IN SELECT column_name FROM information_schema.columns WHERE table_name='bk_probe' AND is_nullable='NO' LOOP EXECUTE format('ALTER TABLE public.bk_probe ALTER COLUMN %I DROP NOT NULL',c); END LOOP; END \$d\$;
 INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES('b4444444-4444-4444-4444-444444444444','$AR','V4','a',ST_GeogFromText('POINT(31 30)'),'ACTIVE','Africa/Cairo','+24');
 INSERT INTO venue.venue_opening_template(id,venue_id,default_local_open,default_local_close,default_full_day,version,updated_at) VALUES('d4444444-4444-4444-4444-444444444444','b4444444-4444-4444-4444-444444444444','10:00','04:00',false,0,now());
 INSERT INTO venue.operating_session(id,venue_id,opening_template_id,operating_day,local_start,local_end,session_period,timezone,generated_from_version,generated_at)
 VALUES(gen_random_uuid(),'b4444444-4444-4444-4444-444444444444','d4444444-4444-4444-4444-444444444444',(now() AT TIME ZONE 'Africa/Cairo')::date,(now() AT TIME ZONE 'Africa/Cairo')-interval '5 hours',(now() AT TIME ZONE 'Africa/Cairo')+interval '5 minutes',tstzrange(now()-interval '5 hours',now()+interval '5 minutes','[)'),'Africa/Cairo',0,now());" 2>&1 | grep ERROR
B="INSERT INTO public.bk_probe(id,venue_id,source,status,created_at,start_at,venue_response_expires_at,booking_fee_snapshot) VALUES(gen_random_uuid(),'$V1','ONLINE','PENDING'"
expect_ok  "F11 valid Online Booking passes" "$B,now(),now()+interval '30 minutes',now()+interval '10 minutes',0)"
expect_err "F11 Start less than 15 minutes ahead rejected" "$B,now(),now()+interval '14 minutes',now()+interval '10 minutes',0)" "15 minutes"
expect_ok  "F11 Start exactly 15 minutes ahead allowed (boundary)" "$B,now(),now()+interval '15 minutes',now()+interval '10 minutes',0)"
expect_err "F11 response deadline must be +10 minutes" "$B,now(),now()+interval '30 minutes',now()+interval '24 hours',0)" "10 minutes"
expect_err "F11 fee snapshot must equal Global Booking Fee" "$B,now(),now()+interval '30 minutes',now()+interval '10 minutes',20)" "Global Booking Fee"
expect_err "F11 creation outside an Operating Session rejected" "$B,now()-interval '3 hours',now()+interval '30 minutes',now()-interval '3 hours'+interval '10 minutes',0)" "Operating Session"
expect_err "F11 intake stops 10 minutes before session end" "INSERT INTO public.bk_probe(id,venue_id,source,status,created_at,start_at,venue_response_expires_at,booking_fee_snapshot) VALUES(gen_random_uuid(),'b4444444-4444-4444-4444-444444444444','ONLINE','PENDING',now(),now()+interval '1 day',now()+interval '10 minutes',0)" "stops 10 minutes"
expect_ok  "F11 Manual Booking not subject to Online intake" "INSERT INTO public.bk_probe(id,venue_id,source,status,created_at,start_at,booking_fee_snapshot) VALUES(gen_random_uuid(),'$V1','MANUAL','CONFIRMED',now(),now(),0)"

# F12 Operating Session: unreferenced session may be regenerated
expect_ok  "F12 unreferenced Operating Session may be regenerated" "UPDATE venue.operating_session SET generated_at=now() WHERE id='$OS'"

# F14 Booking state machine (trigger exercised on a structural copy of booking.booking)
$P -q -c "DROP TABLE IF EXISTS public.bk_sm; CREATE TABLE public.bk_sm (LIKE booking.booking);
 CREATE TRIGGER t BEFORE UPDATE OF status, terminal_reason ON public.bk_sm FOR EACH ROW EXECUTE FUNCTION booking.guard_booking_transition();
 DO \$d\$ DECLARE c text; BEGIN FOR c IN SELECT column_name FROM information_schema.columns WHERE table_name='bk_sm' AND is_nullable='NO' LOOP EXECUTE format('ALTER TABLE public.bk_sm ALTER COLUMN %I DROP NOT NULL',c); END LOOP; END \$d\$;" 2>&1 | grep ERROR
sm(){ # id status source start end
 $P -q -c "INSERT INTO public.bk_sm(id,status,source,start_at,end_at) VALUES('$1','$2','$3',$4,$5)" 2>&1 | grep ERROR; }
K1=10000000-0000-0000-0000-000000000001; K2=10000000-0000-0000-0000-000000000002; K3=10000000-0000-0000-0000-000000000003; K4=10000000-0000-0000-0000-000000000004; K5=10000000-0000-0000-0000-000000000005; K6=10000000-0000-0000-0000-000000000006
sm $K1 PENDING ONLINE "now()+interval '1 day'" "now()+interval '1 day 2 hours'"
sm $K2 VENUE_RESPONSE_EXPIRED ONLINE "now()+interval '1 day'" "now()+interval '1 day 2 hours'"
sm $K3 CONFIRMED MANUAL "now()+interval '1 day'" "now()+interval '1 day 2 hours'"
sm $K4 NO_SHOW ONLINE "now()-interval '1 hour'" "now()+interval '1 hour'"
sm $K5 CONFIRMED ONLINE "now()-interval '1 minute'" "now()+interval '2 hours'"
sm $K6 COMPLETED ONLINE "now()-interval '3 hours'" "now()-interval '1 hour'"
expect_err "F14 PENDING -> COMPLETED rejected" "UPDATE public.bk_sm SET status='COMPLETED' WHERE id='$K1'" "Invalid Booking transition"
expect_err "F14 PENDING -> REJECTED needs VENUE_REJECTED" "UPDATE public.bk_sm SET status='REJECTED',terminal_reason='REJECTED_AFTER_EXPIRY' WHERE id='$K1'" "Invalid Booking transition"
expect_ok  "F14 PENDING -> REJECTED/VENUE_REJECTED" "BEGIN; UPDATE public.bk_sm SET status='REJECTED',terminal_reason='VENUE_REJECTED' WHERE id='$K1'; ROLLBACK;"
expect_err "F14 customer cannot cancel VENUE_RESPONSE_EXPIRED" "UPDATE public.bk_sm SET status='CANCELLED',terminal_reason='CUSTOMER_CANCELLED' WHERE id='$K2'" "Invalid Booking transition"
expect_ok  "F14 VENUE_RESPONSE_EXPIRED -> CANCELLED/VENUE_DID_NOT_RESPOND_IN_TIME" "BEGIN; UPDATE public.bk_sm SET status='CANCELLED',terminal_reason='VENUE_DID_NOT_RESPOND_IN_TIME' WHERE id='$K2'; ROLLBACK;"
expect_err "F14 Manual Booking cannot use VENUE_CANCELLED_CONFIRMED_BOOKING" "UPDATE public.bk_sm SET status='CANCELLED',terminal_reason='VENUE_CANCELLED_CONFIRMED_BOOKING' WHERE id='$K3'" "Invalid Booking transition"
expect_ok  "F14 Manual Booking -> CANCELLED/VENUE_CANCELLED_MANUAL_BOOKING" "BEGIN; UPDATE public.bk_sm SET status='CANCELLED',terminal_reason='VENUE_CANCELLED_MANUAL_BOOKING' WHERE id='$K3'; ROLLBACK;"
expect_err "F14 customer cancellation at/after Start rejected" "UPDATE public.bk_sm SET status='CANCELLED',terminal_reason='CUSTOMER_CANCELLED' WHERE id='$K5'" "Start Time"
expect_ok  "F14 NO_SHOW reversal before endAt -> CANCELLED/ADMIN_NO_SHOW_REVERSAL" "BEGIN; UPDATE public.bk_sm SET status='CANCELLED',terminal_reason='ADMIN_NO_SHOW_REVERSAL' WHERE id='$K4'; ROLLBACK;"
expect_err "F14 NO_SHOW reversal before endAt cannot COMPLETE" "UPDATE public.bk_sm SET status='COMPLETED' WHERE id='$K4'" "before original endAt"
expect_err "F14 terminal Booking is immutable" "UPDATE public.bk_sm SET status='ACTIVE' WHERE id='$K6'" "immutable"

# F15 Booking creation venue eligibility
$P -q -c "DROP TABLE IF EXISTS public.bk_cr; CREATE TABLE public.bk_cr (LIKE booking.booking);
 CREATE TRIGGER t BEFORE INSERT ON public.bk_cr FOR EACH ROW EXECUTE FUNCTION booking.validate_booking_creation_venue();
 DO \$d\$ DECLARE c text; BEGIN FOR c IN SELECT column_name FROM information_schema.columns WHERE table_name='bk_cr' AND is_nullable='NO' LOOP EXECUTE format('ALTER TABLE public.bk_cr ALTER COLUMN %I DROP NOT NULL',c); END LOOP; END \$d\$;
 INSERT INTO iam.venue_account(id,venue_id,login_identifier,password_hash,account_status,language_preference) VALUES(gen_random_uuid(),'$V2','v2','h','ENABLED','en');" 2>&1 | grep ERROR
C="INSERT INTO public.bk_cr(id,venue_id,source,status,created_at,start_at) VALUES(gen_random_uuid()"
expect_ok  "F15 Online Booking at ACTIVE/ENABLED Venue" "$C,'$V1','ONLINE','PENDING',now(),now()+interval '1 hour')"
expect_err "F15 Online Booking at INACTIVE Venue rejected" "$C,'$V2','ONLINE','PENDING',now(),now()+interval '1 hour')" "Marketplace ACTIVE"
expect_ok  "F15 Manual Booking allowed at INACTIVE Venue" "$C,'$V2','MANUAL','CONFIRMED',now(),now()+interval '1 hour')"
expect_err "F15 back-dated Manual Booking rejected" "$C,'$V1','MANUAL','CONFIRMED',now(),now()-interval '1 hour')" "retroactive"
expect_ok  "F15 walk-in Manual Booking ACTIVE at creation instant" "$C,'$V1','MANUAL','ACTIVE',now(),now())"
$P -q -c "UPDATE iam.venue_account SET account_status='DISABLED' WHERE venue_id='$V1'" 2>&1 | grep ERROR
expect_err "F15 Venue Account DISABLED blocks creation" "$C,'$V1','MANUAL','CONFIRMED',now(),now()+interval '1 hour')" "ENABLED"

# F17 Venue Marketplace Status transitions
expect_ok  "F17 ACTIVE -> SUSPENDED allowed" "BEGIN; UPDATE venue.venue SET marketplace_status='SUSPENDED' WHERE id='$V1'; ROLLBACK;"
expect_err "F17 never back to ONBOARDING" "UPDATE venue.venue SET marketplace_status='ONBOARDING' WHERE id='$V1'" "ONBOARDING"


# ---------- F18 Venue Web Push + notification catalogue ----------
VP=f1800000-0000-0000-0000-000000000001
expect_ok  "F18 catalogue seeded from Product §52" "DO \$\$BEGIN IF (SELECT count(*) FROM notification.notification_catalogue)<80 THEN RAISE EXCEPTION 'catalogue empty'; END IF; END\$\$"
expect_ok  "F18 Venue subscription registered" "INSERT INTO notification.venue_push_subscription(id,venue_account_id,endpoint,p256dh_key,auth_key,last_seen_at) VALUES('$VP','$VA','https://push.example/1','k','a',clock_timestamp()+interval '1 second')"
expect_err "F18 duplicate active endpoint rejected" "INSERT INTO notification.venue_push_subscription(id,venue_account_id,endpoint,p256dh_key,auth_key,last_seen_at) VALUES(gen_random_uuid(),'$VA','https://push.example/1','k','a',clock_timestamp()+interval '1 second')"
expect_err "F18 non-https endpoint rejected" "INSERT INTO notification.venue_push_subscription(id,venue_account_id,endpoint,p256dh_key,auth_key,last_seen_at) VALUES(gen_random_uuid(),'$VA','http://push.example/2','k','a',clock_timestamp()+interval '1 second')"
expect_err "F18 keys immutable" "UPDATE notification.venue_push_subscription SET auth_key='b' WHERE id='$VP'" "immutable"
expect_err "F18 last_seen_at never moves back" "UPDATE notification.venue_push_subscription SET last_seen_at=created_at WHERE id='$VP'" "backwards"
expect_err "F18 subscription never deleted" "DELETE FROM notification.venue_push_subscription WHERE id='$VP'" "revoked"
expect_ok  "F18 subscription revoked" "UPDATE notification.venue_push_subscription SET revoked_at=clock_timestamp()+interval '2 second' WHERE id='$VP'"
expect_err "F18 revoked is terminal" "UPDATE notification.venue_push_subscription SET revoked_at=NULL WHERE id='$VP'" "terminal"
expect_ok  "F18 Venue notification (IN_APP+PUSH) accepted" "BEGIN; INSERT INTO notification.notification(id,recipient_type,recipient_id,code) VALUES('f1800000-0000-0000-0000-0000000000a1','VENUE','$V1','VENUE_OWNER_EMERGENCY_NOTICE'); INSERT INTO notification.notification_delivery(id,notification_id,channel,status,attempt) VALUES(gen_random_uuid(),'f1800000-0000-0000-0000-0000000000a1','IN_APP','PENDING',0),(gen_random_uuid(),'f1800000-0000-0000-0000-0000000000a1','PUSH','PENDING',0); COMMIT;"
expect_err "F18 unknown notification code rejected" "INSERT INTO notification.notification(id,recipient_type,recipient_id,code) VALUES(gen_random_uuid(),'VENUE','$V1','NOT_A_CODE')"


# ---------- F19 login rate limit ----------
expect_ok  "F19 LOGIN limiter row accepted" "INSERT INTO iam.security_rate_limit_counter(id,limiter_type,subject_key_hash,action_class,window_started_at,window_seconds,counter,updated_at) VALUES(gen_random_uuid(),'LOGIN','h1','CUSTOMER',now(),900,1,now())"
expect_err "F19 unknown limiter rejected" "INSERT INTO iam.security_rate_limit_counter(id,limiter_type,subject_key_hash,window_started_at,window_seconds,counter,updated_at) VALUES(gen_random_uuid(),'BOGUS','h2',now(),900,1,now())"

echo "RESULT: $pass passed, $fail failed"
