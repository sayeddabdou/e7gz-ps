#!/bin/bash
# Privilege tests: runs Product operations as the runtime roles after the security baseline.
DB=${1:-t127p}
S="psql -h /tmp -p 5499 -U postgres -d $DB -At"
pass=0; fail=0
ok(){ echo "PASS  $1"; pass=$((pass+1)); }
ko(){ echo "FAIL  $1  :: $2"; fail=$((fail+1)); }
as(){ $S -c "SET ROLE $1; $2" 2>&1; }
yes(){ out=$(as $1 "$3"); echo "$out" | grep -q ERROR && ko "$2" "$(echo "$out"|grep ERROR|head -1)" || ok "$2"; }
no(){ out=$(as $1 "$3"); echo "$out" | grep -q "$4" && ok "$2" || ko "$2" "$(echo "$out"|grep ERROR|head -1)"; }
U=11111111-1111-1111-1111-111111111111; U2=22222222-2222-2222-2222-222222222222; A=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
AR=a0000000-0000-0000-0000-000000000001; V=b1111111-1111-1111-1111-111111111111
$S -q -c "INSERT INTO iam.admin_account(id,email,password_hash,full_name,is_active) VALUES('$A','a@x','h','Admin',true);
 INSERT INTO geo.area(id,name,is_active) VALUES('$AR','A',true);
 INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES('$V','$AR','V','a',ST_GeogFromText('POINT(31 30)'),'ACTIVE','Africa/Cairo','+2');" 2>&1 | grep ERROR
APP=e7gz_app_runtime; ADM=e7gz_admin_runtime
yes $APP "app: register Customer (+ commitment guard, loyalty accounts)" "INSERT INTO iam.user_account(id,full_name,username,phone_e164,email,password_hash,phone_verified,phone_verified_at,email_verified,referral_code,joined_at) VALUES('$U','A','a','+2011','a@x','h',true,now(),false,'R1',now()),('$U2','B','b','+2012','b@x','h',true,now(),false,'R2',now())"
yes $APP "app: send OTP" "INSERT INTO iam.otp_challenge(id,user_id,purpose,destination,code_hash,issued_at,expires_at,attempts) VALUES(gen_random_uuid(),'$U','PHONE_VERIFICATION','+2011','c',now(),now()+interval '5 minutes',0)"
yes $APP "app: change username (history written)" "UPDATE iam.user_account SET username='a2' WHERE id='$U'"
yes $APP "app: Customer requests deletion" "BEGIN; SELECT iam.request_account_deletion('$U',gen_random_uuid()); COMMIT;"
yes $APP "app: create Review" "BEGIN; SELECT review.create_review('$U','$V',5::smallint,'good',gen_random_uuid()); COMMIT;"
R=$($S -c "select id from review.review limit 1")
yes $APP "app: report Review" "BEGIN; SELECT review.report_review('$R','$U2','abuse',NULL,gen_random_uuid()); COMMIT;"
no  $APP "app: direct write to deletion requests denied" "INSERT INTO iam.account_deletion_request(id,user_id,requested_at,correlation_id) VALUES(gen_random_uuid(),'$U2',now(),gen_random_uuid())" "permission denied"
no  $APP "app: cannot forge a write token" "SELECT technical.enter_write('review.review','x')" "permission denied"
no  $APP "app: cannot anonymise (admin only)" "SELECT iam.anonymise_user('$U','x')" "permission denied"
no  $APP "app: cannot change configuration (admin only)" "SELECT config.change_configuration('booking.fee','5','x')" "permission denied"
no  $APP "app: cannot DELETE history" "DELETE FROM review.review" "permission denied"
no  $APP "app: cannot TRUNCATE" "TRUNCATE audit.audit_event" "permission denied"
yes $ADM "admin: O19 anonymise completes deletion" "BEGIN; SELECT admin.begin_command('$A',gen_random_uuid(),ARRAY['$U']::uuid[],ARRAY['$V']::uuid[]); SELECT iam.anonymise_user('$U','Customer request'); COMMIT;"
yes $ADM "admin: change Global Booking Fee" "BEGIN; SELECT admin.begin_command('$A',gen_random_uuid(),ARRAY[]::uuid[],ARRAY[]::uuid[]); SELECT config.change_configuration('booking.fee','5'::jsonb,'Launch pricing test'); COMMIT;"
[ "$($S -c "select config.effective_numeric('booking.fee',now())")" = "5" ] && ok "admin: new fee effective for future events" || ko "fee" "not applied"
yes $ADM "admin: resolve report" "BEGIN; SELECT admin.begin_command('$A',gen_random_uuid(),ARRAY[]::uuid[],ARRAY[]::uuid[]); SELECT review.resolve_report(id,'Reviewed',NULL) FROM review.review_report LIMIT 1; COMMIT;"
echo "RESULT: $pass passed, $fail failed"
