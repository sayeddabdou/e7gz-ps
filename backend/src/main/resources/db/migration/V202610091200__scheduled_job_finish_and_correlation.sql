-- =====================================================================================================
-- E7-40 / physical model v1.1.33 — scheduled_job: job outcomes and correlation ID (Product Owner decisions
-- 4 Oct 2026, from the E7-32 review).
--
-- 1. technical.scheduled_job.correlation_id uuid NOT NULL: the correlation ID of the command that created the
--    job; a nightly system job uses its own id. The deadline worker opens it around every attempt, so every retry
--    logs under the same ID (NFR-32, arch 11 section 5.1). Existing rows take their own id. The lifecycle guards
--    refuse any UPDATE of a DONE or SUPERSEDED job and a PENDING -> PENDING rewrite, so the three row triggers are
--    off for the one backfill statement only, inside this migration's transaction.
-- 2. technical.job_finish(p_job, p_worker, p_status, p_error, p_due_at): the lease holder can also mark the job
--    SUPERSEDED (completed_at set), and send it back to PENDING at a later due time (p_due_at) when it fired too
--    early or must back off. p_due_at goes with PENDING only and must be later than the current due_at. The old
--    four-argument function is dropped first, so no call can be ambiguous.
-- 3. technical.guard_scheduled_job(): correlation_id is immutable like the job's identity; due_at may change on
--    LEASED -> PENDING only inside job_finish (its private write token, the Outbox's pattern), never through a
--    plain UPDATE.
--
-- Unchanged on purpose: every other column, constraint, index and trigger; job_lease and job_rearm; the Tournament
-- guards on top (a Tournament job's due_at stays immutable). Grants: PUBLIC is revoked here; the security baseline
-- grants EXECUTE on the new job_finish to both runtime roles after every migration, like every technical function.
-- Platform-lane table (Scheduling, arch 03 module 20).
-- =====================================================================================================
ALTER TABLE technical.scheduled_job ADD COLUMN correlation_id uuid;

ALTER TABLE technical.scheduled_job DISABLE TRIGGER tr_125_scheduled_job_guard;
ALTER TABLE technical.scheduled_job DISABLE TRIGGER tr_guard_tournament_scheduled_job;
ALTER TABLE technical.scheduled_job DISABLE TRIGGER tr_tournament_job_final_state;
UPDATE technical.scheduled_job SET correlation_id = id WHERE correlation_id IS NULL;
ALTER TABLE technical.scheduled_job ENABLE TRIGGER tr_125_scheduled_job_guard;
ALTER TABLE technical.scheduled_job ENABLE TRIGGER tr_guard_tournament_scheduled_job;
ALTER TABLE technical.scheduled_job ENABLE TRIGGER tr_tournament_job_final_state;

ALTER TABLE technical.scheduled_job ALTER COLUMN correlation_id SET NOT NULL;

COMMENT ON TABLE technical.scheduled_job IS E'Durable PostgreSQL scheduler. now < deadline permits; now >= deadline means expiry owns the state. Late workers revalidate and cannot change Product outcome. v1.1.25 generic lifecycle for every job type: PENDING -> LEASED | PAUSED | DONE | SUPERSEDED; PAUSED -> PENDING (explicit re-arm) | SUPERSEDED; LEASED -> DONE | FAILED | PENDING (release; v1.1.33: job_finish may move due_at later) | SUPERSEDED; FAILED -> PENDING (explicit re-arm) | SUPERSEDED; DONE and SUPERSEDED are terminal. type/subject/idempotency_key/correlation_id are immutable; due_at moves only through job_rearm or job_finish.';
COMMENT ON COLUMN technical.scheduled_job.correlation_id IS E'Correlation ID of the command that created the job; a nightly system job uses its own id. The worker opens it around every attempt and every retry reuses it (v1.1.33, NFR-32).';

-- v1.1.33 functions (.dbm block v1_1_33_scheduled_job_finish_and_correlation)
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
 ELSIF NEW.attempt<>OLD.attempt THEN RAISE EXCEPTION 'attempt changes only when a lease is taken';
 END IF;
 IF NEW.due_at<>OLD.due_at AND NOT (NEW.status='PENDING' AND (OLD.status IN ('PAUSED','FAILED')
      OR (OLD.status='LEASED' AND technical.has_write('technical.scheduled_job',OLD.id::text)))) THEN
   RAISE EXCEPTION 'due_at changes only through the explicit pause/re-arm path or job_finish';
 END IF;
 RETURN NEW;
END;
$function$;

DROP FUNCTION technical.job_finish(uuid,text,text,text);
CREATE OR REPLACE FUNCTION technical.job_finish(p_job uuid,p_worker text,p_status text,p_error text DEFAULT NULL,p_due_at timestamptz DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE j technical.scheduled_job%ROWTYPE; tok uuid;
BEGIN
 SELECT * INTO j FROM technical.scheduled_job WHERE id=p_job FOR UPDATE;
 IF NOT FOUND OR j.status<>'LEASED' OR j.leased_by IS DISTINCT FROM p_worker OR j.leased_until<=clock_timestamp() THEN
   RAISE EXCEPTION 'Only the current lease holder can finish this job';
 END IF;
 IF p_status NOT IN ('DONE','SUPERSEDED','FAILED','PENDING') THEN RAISE EXCEPTION 'Invalid job outcome'; END IF;
 IF p_due_at IS NOT NULL AND p_status<>'PENDING' THEN RAISE EXCEPTION 'Only a job sent back to PENDING takes a new due time'; END IF;
 IF p_due_at IS NOT NULL AND p_due_at<=j.due_at THEN RAISE EXCEPTION 'A job is only ever moved later'; END IF;
 IF p_due_at IS NOT NULL THEN tok:=technical.enter_write('technical.scheduled_job',j.id::text); END IF;
 UPDATE technical.scheduled_job SET status=p_status,due_at=COALESCE(p_due_at,j.due_at),leased_at=NULL,leased_until=NULL,leased_by=NULL,
   completed_at=CASE WHEN p_status IN ('DONE','SUPERSEDED','FAILED') THEN clock_timestamp() END,
   last_error=CASE WHEN p_status IN ('DONE','SUPERSEDED') THEN j.last_error ELSE p_error END
  WHERE id=j.id;
 IF tok IS NOT NULL THEN PERFORM technical.leave_write(tok); END IF;
END;
$function$;
REVOKE ALL ON FUNCTION technical.job_finish(uuid,text,text,text,timestamptz) FROM PUBLIC;
