#!/bin/bash
# Isolated unit tests for the v1.1.27 Tournament approval / Ban-boundary changes.
# The command-scope and creation-marker helpers are stubbed with a test clock; unrelated triggers on the
# tables under test are disabled so only the changed guards are exercised.
DB=${1:-t127t}
P="psql -h /tmp -p 5499 -U postgres -d $DB -At"
pass=0; fail=0
ok(){ echo "PASS  $1"; pass=$((pass+1)); }
ko(){ echo "FAIL  $1  :: $2"; fail=$((fail+1)); }
run(){ $P -c "SET test.now='$NOW'; $2" 2>&1; }
expect_ok(){ out=$(run "$1" "$2"); if echo "$out" | grep -q ERROR; then ko "$1" "$(echo "$out"|grep ERROR|head -1)"; else ok "$1"; fi; }
expect_err(){ out=$(run "$1" "$2"); if echo "$out" | grep -q ERROR; then if [ -n "$3" ] && ! echo "$out" | grep -q "$3"; then ko "$1" "wrong error: $(echo "$out"|grep ERROR|head -1)"; else ok "$1"; fi; else ko "$1" "expected an error"; fi; }

U=11111111-1111-1111-1111-111111111111; U2=22222222-2222-2222-2222-222222222222
AR=a0000000-0000-0000-0000-000000000001; V=b1111111-1111-1111-1111-111111111111; VA=c1111111-1111-1111-1111-111111111111
TPL=d1111111-1111-1111-1111-111111111111; OS=e1111111-1111-1111-1111-111111111111
T0=2026-10-01T12:00:00Z
$P -q <<SQL 2>&1 | grep ERROR
CREATE OR REPLACE FUNCTION tournament.require_command_scope(p_tournament_id uuid DEFAULT NULL,p_venue_id uuid DEFAULT NULL,p_resource_id uuid DEFAULT NULL,p_user_id uuid DEFAULT NULL) RETURNS timestamptz LANGUAGE sql AS \$\$ SELECT current_setting('test.now')::timestamptz \$\$;
CREATE OR REPLACE FUNCTION tournament.mark_created_in_command(p_tournament_id uuid) RETURNS void LANGUAGE sql AS \$\$ SELECT \$\$;
ALTER TABLE tournament.tournament DISABLE TRIGGER USER;
ALTER TABLE tournament.tournament ENABLE TRIGGER tr_00_prepare_tournament_insert;
ALTER TABLE tournament.tournament ENABLE TRIGGER tr_10_guard_tournament_update;
ALTER TABLE tournament.tournament ENABLE TRIGGER tr_00_tournament_due_boundary;
ALTER TABLE tournament.tournament_history DISABLE TRIGGER USER;
ALTER TABLE tournament.tournament_participant DISABLE TRIGGER USER;
ALTER TABLE trust.trust_projection DISABLE TRIGGER USER;
ALTER TABLE trust.ban_instance DISABLE TRIGGER USER;
ALTER TABLE iam.user_account DISABLE TRIGGER USER;
INSERT INTO iam.user_account(id,full_name,username,phone_e164,email,password_hash,phone_verified,phone_verified_at,email_verified,referral_code,joined_at) VALUES
 ('$U','P','p','+2011','p@x','h',true,now(),false,'R1',now()),('$U2','Q','q','+2012','q@x','h',true,now(),false,'R2',now());
INSERT INTO trust.trust_projection(user_id,vp_operational_status,projection_version) VALUES('$U','VERIFIED',0);
INSERT INTO geo.area(id,name,is_active) VALUES('$AR','A',true);
INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES('$V','$AR','V','a',ST_GeogFromText('POINT(31 30)'),'ACTIVE','Africa/Cairo','+2');
INSERT INTO iam.venue_account(id,venue_id,login_identifier,password_hash,account_status,language_preference) VALUES('$VA','$V','v','h','ENABLED','en');
INSERT INTO venue.venue_activity(id,venue_id,activity) VALUES(gen_random_uuid(),'$V','PLAYSTATION');
INSERT INTO venue.venue_opening_template(id,venue_id,default_local_open,default_local_close,default_full_day,version,updated_at) VALUES('$TPL','$V','00:00','00:00',true,0,now());
INSERT INTO venue.operating_session(id,venue_id,opening_template_id,operating_day,local_start,local_end,session_period,timezone,generated_from_version,generated_at)
 VALUES('$OS','$V','$TPL','2026-10-01','2026-10-01 00:00','2026-10-02 00:00',tstzrange('2026-09-30T21:00:00Z','2026-10-01T21:00:00Z','[)'),'Africa/Cairo',0,now());
SQL

ins(){ # id start creator_fee
 echo "INSERT INTO tournament.tournament(id,venue_id,host_type,creator_user_id,creator_participates,activity,play_mode,status,capacity,start_at,nominal_minutes,visibility,venue_cost_total,creation_fee_snapshot,entry_fee,prize,business_date,operating_session_id,version,created_at,end_at,checkpoint_at)
  VALUES('$1','$V','PLAYER','$U',true,'PLAYSTATION','SINGLE','PENDING_VENUE_APPROVAL',8,'$2',120,'PUBLIC',0,$3,0,0,'2026-10-01','$OS',0,now(),now(),now())"; }
TA=f0000000-0000-0000-0000-00000000000a; TX=f0000000-0000-0000-0000-0000000000ff; TB=f0000000-0000-0000-0000-00000000000b; TC=f0000000-0000-0000-0000-00000000000c; TD=f0000000-0000-0000-0000-00000000000d

NOW=$T0
expect_err "F13 Player request inside startAt-2h rejected" "$(ins $TA 2026-10-01T13:59:00Z 0)" "startAt - 2 hours"
expect_ok  "F13 Player request at exactly startAt-2h accepted (previously needed 25 h)" "$(ins $TX 2026-10-01T14:00:00Z 0)"
$P -q -c "SET session_replication_role=replica; DELETE FROM tournament.tournament WHERE id='$TX'"
expect_ok  "setup: request A" "$(ins $TA 2026-10-01T16:00:00Z 0)"
[ "$(run x "select approval_expires_at = '$T0'::timestamptz + interval '10 minutes' from tournament.tournament where id='$TA'" | tail -1)" = "t" ] && ok "F13 approvalExpiresAt = created + 10 min" || ko F13 "approval"
expect_err "F13 creation fee must equal configured fee" "$(ins $TB 2026-10-01T18:00:00Z 5)" "Creation Fee"
$P -q -c "UPDATE tournament.tournament SET status=status WHERE false" >/dev/null
expect_err "F13 one PENDING request per Creator still enforced" "$(ins $TB 2026-10-01T18:00:00Z 0)" "ux_tournament_one_pending_creator"

NOW=2026-10-01T12:05:00Z
expect_err "F13 Venue Reject without reason rejected" "UPDATE tournament.tournament SET status='REJECTED',version=1 WHERE id='$TA'"
expect_err "F13 VENUE_DID_NOT_APPROVE before expiry rejected" "UPDATE tournament.tournament SET status='CANCELLED',terminal_reason='VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME',version=1 WHERE id='$TA'" "at/after approvalExpiresAt"
expect_err "F13 VENUE_REJECTED_TOURNAMENT is not a cancellation reason" "UPDATE tournament.tournament SET status='CANCELLED',terminal_reason='VENUE_REJECTED_TOURNAMENT',version=1 WHERE id='$TA'"
expect_ok  "F13 Venue Reject before expiry -> REJECTED/VENUE_REJECTED_TOURNAMENT" "UPDATE tournament.tournament SET status='REJECTED',terminal_reason='VENUE_REJECTED_TOURNAMENT',version=1 WHERE id='$TA'"
$P -q -c "INSERT INTO tournament.tournament_history(id,tournament_id,sequence,occurred_at,from_status,to_status,terminal_reason,actor_type,actor_id,correlation_id) VALUES(gen_random_uuid(),'$TA',1,'$T0',NULL,'PENDING_VENUE_APPROVAL',NULL,'CUSTOMER','$U','00000000-0000-0000-0000-0000000000a1'),(gen_random_uuid(),'$TA',2,'2026-10-01T12:05:00Z','PENDING_VENUE_APPROVAL','REJECTED','VENUE_REJECTED_TOURNAMENT','VENUE','$VA','00000000-0000-0000-0000-0000000000a2');
 SET session_replication_role=replica;
 INSERT INTO audit.audit_event(id,occurred_at,actor_type,actor_id,action,subject_type,subject_id,correlation_id,payload) VALUES
 (gen_random_uuid(),'$T0','CUSTOMER','$U','TOURNAMENT_STATUS_TRANSITION','TOURNAMENT','$TA','00000000-0000-0000-0000-0000000000a1','{}'),
 (gen_random_uuid(),'2026-10-01T12:05:00Z','VENUE','$VA','TOURNAMENT_STATUS_TRANSITION','TOURNAMENT','$TA','00000000-0000-0000-0000-0000000000a2','{}');" 2>&1 | grep ERROR
NOW=2026-10-01T12:08:00Z
expect_err "F13 same Venue/interval inside 5 min after REJECTED rejected" "$(ins $TB 2026-10-01T16:00:00Z 0)" "5 minutes"
NOW=2026-10-01T12:10:00Z
expect_ok  "F13 same Venue/interval after 5 min allowed" "$(ins $TB 2026-10-01T16:00:00Z 0)"

NOW=2026-10-01T12:20:00Z
expect_err "F13 Venue Reject at/after expiry rejected" "UPDATE tournament.tournament SET status='REJECTED',terminal_reason='VENUE_REJECTED_TOURNAMENT',version=1 WHERE id='$TB'" "Approval deadline owns"
expect_err "F13 expiry cannot be REJECTED without reason" "UPDATE tournament.tournament SET status='REJECTED',version=1 WHERE id='$TB'"
expect_ok  "F13 approval timeout -> CANCELLED/VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME" "UPDATE tournament.tournament SET status='CANCELLED',terminal_reason='VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME',version=1 WHERE id='$TB'"
$P -q -c "INSERT INTO tournament.tournament_history(id,tournament_id,sequence,occurred_at,from_status,to_status,terminal_reason,actor_type,actor_id,correlation_id) VALUES(gen_random_uuid(),'$TB',1,'2026-10-01T12:10:00Z',NULL,'PENDING_VENUE_APPROVAL',NULL,'CUSTOMER','$U','00000000-0000-0000-0000-0000000000b1'),(gen_random_uuid(),'$TB',2,'2026-10-01T12:20:00Z','PENDING_VENUE_APPROVAL','CANCELLED','VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME','SYSTEM',NULL,'00000000-0000-0000-0000-0000000000b2');
 SET session_replication_role=replica;
 INSERT INTO audit.audit_event(id,occurred_at,actor_type,actor_id,action,subject_type,subject_id,correlation_id,payload) VALUES
 (gen_random_uuid(),'2026-10-01T12:10:00Z','CUSTOMER','$U','TOURNAMENT_STATUS_TRANSITION','TOURNAMENT','$TB','00000000-0000-0000-0000-0000000000b1','{}'),
 (gen_random_uuid(),'2026-10-01T12:20:00Z','SYSTEM',NULL,'TOURNAMENT_STATUS_TRANSITION','TOURNAMENT','$TB','00000000-0000-0000-0000-0000000000b2','{}');" 2>&1 | grep ERROR
NOW=2026-10-01T12:22:00Z
expect_err "F13 same Venue/interval inside 5 min after approval EXPIRED rejected" "$(ins $TC 2026-10-01T16:00:00Z 0)" "5 minutes"
expect_err "F13 history: REJECTED row without reason rejected" "INSERT INTO tournament.tournament_history(id,tournament_id,sequence,occurred_at,from_status,to_status,terminal_reason,actor_type,actor_id,correlation_id) VALUES(gen_random_uuid(),'$TB',9,now(),'PENDING_VENUE_APPROVAL','REJECTED',NULL,'SYSTEM',NULL,gen_random_uuid())" "ck_tournament_history"
[ "$(run x "select count(*) from pg_constraint where conname='ck_tournament_rejected_reason'" | tail -1)" = "1" ] && ok "F13 REJECTED <=> VENUE_REJECTED_TOURNAMENT constraint present" || ko F13 ck

# provenance + history chain functions on the two terminal tournaments
out=$(run x "SELECT tournament.assert_tournament_history_chain('$TA')" ); echo "$out" | grep -q "ERROR" && ko "F13 history chain accepts Venue REJECTED/VENUE_REJECTED_TOURNAMENT" "$out" || ok "F13 history chain accepts Venue REJECTED/VENUE_REJECTED_TOURNAMENT ($(echo "$out"|grep ERROR|cut -c1-90))"
out=$(run x "SELECT tournament.assert_tournament_history_chain('$TB')" ); echo "$out" | grep -q "ERROR" && ko "F13 history chain accepts SYSTEM timeout cancellation" "$out" || ok "F13 history chain accepts SYSTEM timeout cancellation ($(echo "$out"|grep ERROR|cut -c1-90))"

# F02 Ban ending exactly at startAt (Tournament start boundary)
TS=f0000000-0000-0000-0000-0000000000e1
$P -q <<SQL 2>&1 | grep ERROR
ALTER TABLE tournament.tournament DISABLE TRIGGER USER;
INSERT INTO tournament.tournament(id,venue_id,host_type,creator_user_id,creator_participates,activity,play_mode,status,capacity,created_at,start_at,end_at,nominal_minutes,checkpoint_at,approval_expires_at,visibility,venue_cost_total,creation_fee_snapshot,entry_fee,prize,business_date,operating_session_id,version)
 VALUES('$TS','$V','VENUE',NULL,NULL,'PLAYSTATION','SINGLE','CONFIRMED',8,'2026-10-01T08:00:00Z','2026-10-01T16:00:00Z','2026-10-01T18:00:00Z',120,'2026-10-01T15:00:00Z',NULL,'PUBLIC',0,0,0,0,'2026-10-01','$OS',2);
ALTER TABLE tournament.tournament ENABLE TRIGGER tr_00_tournament_due_boundary;
INSERT INTO tournament.tournament_participant(id,tournament_id,user_id,status,joined_at,status_changed_at,ineligible_reason) VALUES(gen_random_uuid(),'$TS','$U2','INELIGIBLE','2026-10-01T09:00:00Z','2026-10-01T10:00:00Z','BAN');
SET session_replication_role=replica;
INSERT INTO trust.ban_instance(id,user_id,qualification_id,penalty_stage,activated_at,ends_at,duration,status,activation_reason,latest_qualification_id,last_command_at)
 VALUES(gen_random_uuid(),'$U2',gen_random_uuid(),1,'2026-10-01T10:00:00Z','2026-10-01T16:00:00Z',interval '6 hours','EXPIRED','test',gen_random_uuid(),'2026-10-01T10:00:00Z');
SET session_replication_role=origin;
SQL
NOW=2026-10-01T16:00:05Z
out=$(run x "BEGIN; UPDATE tournament.tournament SET status='ACTIVE',version=3 WHERE id='$TS'; ROLLBACK;")
echo "$out" | grep -q "Restore BAN-only Participants whose Ban expired by startAt" && ko "F02 Ban ending exactly at startAt does not require restoration" "$out" || ok "F02 Ban ending exactly at startAt does not require restoration"
$P -q -c "ALTER TABLE trust.ban_instance DISABLE TRIGGER USER; UPDATE trust.ban_instance SET ends_at='2026-10-01T15:59:59Z',duration=interval '5 hours 59 minutes 59 seconds' WHERE user_id='$U2'" 2>&1 | grep ERROR
out=$(run x "BEGIN; UPDATE tournament.tournament SET status='ACTIVE',version=3 WHERE id='$TS'; ROLLBACK;")
echo "$out" | grep -q "Restore BAN-only Participants whose Ban expired by startAt" && ok "F02 Ban ending 1 s before startAt still requires restoration" || ko "F02 Ban 1 s before startAt" "$out"

echo "RESULT: $pass passed, $fail failed"
