-- Upgrade-path seed, loaded right after V202611271200 (PCR-014, v1.1.47): the Customer of the v1.1.43 seed (…241)
-- blocks the V1 seed Customer (…041), unblocks and blocks again, so every later migration runs over a lifted block
-- and an open one. One command instant for all three writes. Run as the owner, in one transaction - test setup only.

DO $seed_v1_1_47$
DECLARE at timestamptz;
BEGIN
 at := tournament.begin_command(ARRAY[]::uuid[], ARRAY[]::uuid[], ARRAY[]::uuid[], ARRAY[]::uuid[]);
 INSERT INTO review.review_author_block (id, customer_user_id, blocked_user_id, blocked_at)
 VALUES ('5afe0000-0000-4000-8000-000000000471', '5afe0000-0000-4000-8000-000000000241',
         '5afe0000-0000-4000-8000-000000000041', at);
 UPDATE review.review_author_block SET unblocked_at = at WHERE id = '5afe0000-0000-4000-8000-000000000471';
 INSERT INTO review.review_author_block (id, customer_user_id, blocked_user_id, blocked_at)
 VALUES ('5afe0000-0000-4000-8000-000000000472', '5afe0000-0000-4000-8000-000000000241',
         '5afe0000-0000-4000-8000-000000000041', at);
END;
$seed_v1_1_47$;
