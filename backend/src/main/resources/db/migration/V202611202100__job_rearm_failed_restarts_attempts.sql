-- =====================================================================================================
-- E7-83 / physical model v1.1.46 — re-arming a FAILED job starts its attempts again (FR-OPS-36; Sprint 2 review, PO
-- decision 12 Oct 2026: resume re-arms the paused booking's T1 whether it is PAUSED or FAILED). For Product Owner
-- review.
--
-- Before: technical.job_rearm sent a FAILED job back to PENDING with its attempt count unchanged - already at the
-- deadline worker's maximum - so the next lease recorded one attempt more than allowed and the worker marked the job
-- FAILED again without running it ("attempts exhausted"). A re-armed FAILED job could never fire, and a T1 that fired
-- during an emergency pause (it refuses to run while paused, backs off and ends FAILED) could never expire the booking
-- after the resume.
--
-- 1. technical.job_rearm: as V1, plus - a FAILED job re-armed starts its attempts again at 0 (a new run of the same
--    job), through the guard's private write token, the job_finish pattern. A PAUSED job keeps its count: a pause is
--    not a new run.
-- 2. technical.guard_scheduled_job: its V202610091200 definition with one more permitted attempt change - back to 0 on
--    FAILED -> PENDING, only inside job_rearm (the write token), never through a plain UPDATE.
--
-- Unchanged on purpose: every table, column, constraint, index, trigger and grant; job_lease and job_finish; the
-- Tournament job guards on top. Existing rows are not touched (a FAILED job keeps its count until it is re-armed).
-- Grants: CREATE OR REPLACE keeps both functions' grants; the security baseline grants them again after every
-- migration. Platform-lane table (Scheduling, arch 03 module 20).
-- =====================================================================================================
CREATE OR REPLACE FUNCTION technical.guard_scheduled_job()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE ok boolean;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Scheduled jobs are SUPERSEDED, not deleted'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.status NOT IN ('PENDING','PAUSED') OR NEW.attempt<>0 OR NEW.completed_at IS NOT NULL OR NEW.last_error IS NOT NULL THEN
     RAISE EXCEPTION 'A new job starts PENDING or PAUSED with no attempt';
   END IF;
   IF NEW.created_at>clock_timestamp() THEN RAISE EXCEPTION 'Job created_at cannot be in the future'; END IF;
   RETURN NEW;
 END IF;
 IF NEW.id<>OLD.id OR NEW.type<>OLD.type OR NEW.subject_type<>OLD.subject_type OR NEW.subject_id<>OLD.subject_id
    OR NEW.idempotency_key<>OLD.idempotency_key OR NEW.created_at<>OLD.created_at OR NEW.correlation_id<>OLD.correlation_id THEN
   RAISE EXCEPTION 'Job type/subject/idempotency/correlation identity is immutable';
 END IF;
 IF OLD.status IN ('DONE','SUPERSEDED') THEN RAISE EXCEPTION 'Job is % and can never fire again',OLD.status; END IF;
 ok:=(OLD.status='PENDING' AND NEW.status IN ('LEASED','PAUSED','DONE','SUPERSEDED'))
   OR (OLD.status='PAUSED' AND NEW.status IN ('PENDING','SUPERSEDED'))
   OR (OLD.status='LEASED' AND NEW.status IN ('DONE','FAILED','PENDING','SUPERSEDED'))
   OR (OLD.status='LEASED' AND NEW.status='LEASED' AND OLD.leased_until<=clock_timestamp())
   OR (OLD.status='FAILED' AND NEW.status IN ('PENDING','SUPERSEDED'));
 IF NOT ok THEN RAISE EXCEPTION 'Invalid job transition % -> %',OLD.status,NEW.status; END IF;
 IF NEW.status='LEASED' THEN
   IF NEW.attempt<>OLD.attempt+1 THEN RAISE EXCEPTION 'A lease records exactly one attempt'; END IF;
 ELSIF NEW.attempt<>OLD.attempt AND NOT (NEW.attempt=0 AND NEW.status='PENDING' AND OLD.status='LEASED'
      AND NEW.due_at>OLD.due_at AND technical.has_write('technical.scheduled_job',OLD.id::text))
      AND NOT (NEW.attempt=0 AND NEW.status='PENDING' AND OLD.status='FAILED'
      AND technical.has_write('technical.scheduled_job',OLD.id::text)) THEN
   RAISE EXCEPTION 'attempt changes only when a lease is taken, or starts again on a next run through job_finish';
 END IF;
 IF NEW.due_at<>OLD.due_at AND NOT (NEW.status='PENDING' AND (OLD.status IN ('PAUSED','FAILED')
      OR (OLD.status='LEASED' AND technical.has_write('technical.scheduled_job',OLD.id::text)))) THEN
   RAISE EXCEPTION 'due_at changes only through the explicit pause/re-arm path or job_finish';
 END IF;
 RETURN NEW;
END;
$function$;
CREATE OR REPLACE FUNCTION technical.job_rearm(p_job uuid,p_due_at timestamptz)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_status text; tok uuid;
BEGIN
 SELECT status INTO v_status FROM technical.scheduled_job WHERE id=p_job FOR UPDATE;
 IF v_status='FAILED' THEN tok:=technical.enter_write('technical.scheduled_job',p_job::text); END IF;
 UPDATE technical.scheduled_job SET status='PENDING',due_at=COALESCE(p_due_at,due_at),completed_at=NULL,
   attempt=CASE WHEN status='FAILED' THEN 0 ELSE attempt END
  WHERE id=p_job AND status IN ('PAUSED','FAILED');
 IF NOT FOUND THEN RAISE EXCEPTION 'Only a PAUSED or FAILED job can be re-armed'; END IF;
 IF tok IS NOT NULL THEN PERFORM technical.leave_write(tok); END IF;
END;
$function$;
