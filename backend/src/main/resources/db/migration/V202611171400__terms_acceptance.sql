-- =====================================================================================================
-- E7-124 / physical model v1.1.43 — the recorded acceptance of the Terms of Use and the Privacy Policy at sign-up
-- (PCR-013 items 4-6, Product Owner decisions 15 and 16 Nov 2026).
--
--   1. iam.terms_acceptance: one row per Customer who accepted the texts at registration - the version label they
--      accepted and the instant. Written once, in the registering command, and never changed or removed.
--   2. iam.guard_terms_acceptance: the row is append-only, and only the command that creates the account may write it:
--      accepted_at is that command's instant (tournament.command_now()) and the account's joined_at. So an account
--      created before this change can never be given an acceptance later (PCR-013 item 6: nothing is invented).
--
-- Whether the version is the CURRENT one is the application's check (a deployment setting, E7GZ_LEGAL_TERMS_VERSION);
-- the database stores what was accepted. A later version that existing Customers must accept again needs its own PCR
-- (PCR-013 item 5) and a new migration. Existing accounts get no row: no backfill. An anonymised account keeps its row:
-- it holds no personal data, and it is the proof of consent (Law 151/2020). Grants: the security baseline grants
-- SELECT/INSERT/UPDATE on every iam table to both runtime roles; the guard refuses every change after the INSERT.
-- =====================================================================================================

CREATE TABLE iam.terms_acceptance (
	user_id uuid NOT NULL,
	terms_version text NOT NULL,
	accepted_at timestamp with time zone NOT NULL,
	CONSTRAINT terms_acceptance_pk PRIMARY KEY (user_id),
	CONSTRAINT ck_terms_acceptance_version CHECK (terms_version = btrim(terms_version) AND length(terms_version) BETWEEN 1 AND 64)
);
COMMENT ON TABLE iam.terms_acceptance IS E'APPEND-ONLY. PCR-013 items 4-6: the version of the Terms of Use and the Privacy Policy a Customer accepted at registration, and when. Written once, by the command that creates the account (accepted_at = its command instant = joined_at); never changed. Accounts created before PCR-013 have no row.';
COMMENT ON COLUMN iam.terms_acceptance.terms_version IS E'The version label of the pair of texts (PCR-013 item 5), e.g. a date such as 2026-11-15';
COMMENT ON COLUMN iam.terms_acceptance.accepted_at IS E'The registering command instant; never device time';
ALTER TABLE iam.terms_acceptance ADD CONSTRAINT fk_terms_acceptance_user FOREIGN KEY (user_id)
 REFERENCES iam.user_account (id) MATCH SIMPLE ON DELETE RESTRICT ON UPDATE NO ACTION;

CREATE OR REPLACE FUNCTION iam.guard_terms_acceptance()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
DECLARE v_joined timestamptz;
BEGIN
 IF TG_OP<>'INSERT' THEN
   RAISE EXCEPTION 'iam.terms_acceptance is written once at registration and never changed (PCR-013 item 4)';
 END IF;
 SELECT u.joined_at INTO v_joined FROM iam.user_account u WHERE u.id=NEW.user_id AND u.anonymised_at IS NULL;
 IF NOT FOUND THEN RAISE EXCEPTION 'Terms acceptance requires an existing, not anonymised Customer'; END IF;
 -- PCR-013 items 4 and 6: only the registering command records it, at its own instant
 IF NEW.accepted_at IS DISTINCT FROM tournament.command_now() OR NEW.accepted_at IS DISTINCT FROM v_joined THEN
   RAISE EXCEPTION 'Terms acceptance is recorded only by the command that registers the Customer, at its instant';
 END IF;
 RETURN NEW;
END;
$function$;
REVOKE ALL ON FUNCTION iam.guard_terms_acceptance() FROM PUBLIC;
CREATE TRIGGER tr_141_terms_acceptance_guard BEFORE INSERT OR UPDATE OR DELETE ON iam.terms_acceptance
 FOR EACH ROW EXECUTE FUNCTION iam.guard_terms_acceptance();
CREATE TRIGGER tr_141_terms_acceptance_no_truncate BEFORE TRUNCATE ON iam.terms_acceptance
 FOR EACH STATEMENT EXECUTE FUNCTION technical.reject_truncate();
