-- =====================================================================================================
-- E7-102 / physical model v1.1.47 — a Customer blocks the author of a Review (PCR-014, FR-REV-12; Product Owner
-- decision 16 Nov 2026). For Product Owner review.
--
--   1. review.review_author_block: one row per block - the Customer who blocked, the blocked User, the instant
--      (PCR-014 "one table (the Customer, the blocked User, the instant)"). An unblock does not delete the row: it
--      sets unblocked_at once (nothing is hard-deleted, and the runtime roles have no DELETE grant on this schema). A
--      later block of the same User is a new row; ux_review_author_block_open keeps at most one open block per pair.
--   2. review.guard_review_author_block: no DELETE or TRUNCATE; a new row is open and dated the command instant
--      (tournament.command_now()), so it is written only inside a command; the one change allowed is the unblock,
--      once, at the command instant. A Customer cannot block themself (ck_review_author_block_self).
--
-- A block changes nothing else (PCR-014 items 3, 4, 7): not the Review, its status, the rating, moderation, nor any
-- other reader; it raises no notification and no audit. Which Reviews a Customer reads is the application's filter.
-- When either account is deleted the block simply has no further effect (PCR-014 item 8): the row is kept. Grants:
-- the security baseline grants SELECT/INSERT/UPDATE on every review table to both runtime roles. No backfill.
-- =====================================================================================================

CREATE TABLE review.review_author_block (
	id uuid NOT NULL,
	customer_user_id uuid NOT NULL,
	blocked_user_id uuid NOT NULL,
	blocked_at timestamp with time zone NOT NULL,
	unblocked_at timestamp with time zone,
	CONSTRAINT review_author_block_pk PRIMARY KEY (id),
	CONSTRAINT ck_review_author_block_self CHECK (customer_user_id <> blocked_user_id),
	CONSTRAINT ck_review_author_block_window CHECK (unblocked_at IS NULL OR unblocked_at >= blocked_at)
);
COMMENT ON TABLE review.review_author_block IS E'PCR-014 / FR-REV-12. A Customer blocked the author of a Review: that Customer no longer sees any Review by the blocked User, on any Venue. Nobody else is affected and the author is never told. Unblock sets unblocked_at once; a new block is a new row. Never deleted.';
COMMENT ON COLUMN review.review_author_block.customer_user_id IS E'The Customer who blocked';
COMMENT ON COLUMN review.review_author_block.blocked_user_id IS E'The blocked author';
COMMENT ON COLUMN review.review_author_block.blocked_at IS E'The blocking command instant';
COMMENT ON COLUMN review.review_author_block.unblocked_at IS E'The unblocking command instant; NULL while the block is in force';
ALTER TABLE review.review_author_block ADD CONSTRAINT fk_review_author_block_customer FOREIGN KEY (customer_user_id)
 REFERENCES iam.user_account (id) MATCH SIMPLE ON DELETE RESTRICT ON UPDATE NO ACTION;
ALTER TABLE review.review_author_block ADD CONSTRAINT fk_review_author_block_blocked FOREIGN KEY (blocked_user_id)
 REFERENCES iam.user_account (id) MATCH SIMPLE ON DELETE RESTRICT ON UPDATE NO ACTION;
CREATE UNIQUE INDEX ux_review_author_block_open ON review.review_author_block
USING btree
(
	customer_user_id,
	blocked_user_id
)
WITH (FILLFACTOR = 90)
WHERE (unblocked_at IS NULL);
CREATE INDEX ix_fk_review_author_block_blocked_user_id ON review.review_author_block
USING btree
(
	blocked_user_id
)
WITH (FILLFACTOR = 90);

CREATE OR REPLACE FUNCTION review.guard_review_author_block()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pg_temp AS $function$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'A Review author block is never deleted; unblock sets unblocked_at'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.unblocked_at IS NOT NULL THEN RAISE EXCEPTION 'A new Review author block is open'; END IF;
   IF NEW.blocked_at IS DISTINCT FROM tournament.command_now() THEN
     RAISE EXCEPTION 'A Review author block is dated its command instant';
   END IF;
   RETURN NEW;
 END IF;
 IF NEW.id<>OLD.id OR NEW.customer_user_id<>OLD.customer_user_id OR NEW.blocked_user_id<>OLD.blocked_user_id
    OR NEW.blocked_at<>OLD.blocked_at THEN
   RAISE EXCEPTION 'Review author block identity and blocked_at are immutable';
 END IF;
 IF OLD.unblocked_at IS NOT NULL THEN RAISE EXCEPTION 'A lifted Review author block is immutable'; END IF;
 IF NEW.unblocked_at IS DISTINCT FROM tournament.command_now() THEN
   RAISE EXCEPTION 'An unblock is dated its command instant';
 END IF;
 RETURN NEW;
END;
$function$;
REVOKE ALL ON FUNCTION review.guard_review_author_block() FROM PUBLIC;
CREATE TRIGGER tr_147_review_author_block_guard BEFORE INSERT OR UPDATE OR DELETE ON review.review_author_block
 FOR EACH ROW EXECUTE FUNCTION review.guard_review_author_block();
CREATE TRIGGER tr_147_review_author_block_no_truncate BEFORE TRUNCATE ON review.review_author_block
 FOR EACH STATEMENT EXECUTE FUNCTION technical.reject_truncate();
