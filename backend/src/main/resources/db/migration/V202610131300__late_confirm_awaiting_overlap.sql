-- =====================================================================================================
-- E7-58 / physical model v1.1.35 — a Late Confirm may wait on the customer despite a schedule conflict
-- (Product Owner decision D1, 12 Oct 2026, E7-58 discovery; FR-LCF-15, FR-LCF-16).
--
-- FR-LCF-15/16: the venue may always send a Late Confirm; the customer's schedule conflict is checked only when the
-- customer accepts. V1's ex_customer_no_overlap refused VENUE_RESPONSE_EXPIRED -> AWAITING_CUSTOMER_CONFIRMATION
-- whenever that interval overlapped another counted commitment of the customer (23P01), because
-- booking.derive_commitment_period gives an AWAITING booking its commitment_period. One change, nothing else:
--
-- 1. ex_customer_no_overlap is dropped and re-added under the same name, with the same columns and operators
--    (customer_id WITH =, commitment_period WITH &&, gist) and the predicate narrowed to
--    (commitment_period IS NOT NULL AND status <> 'AWAITING_CUSTOMER_CONFIRMATION'). An AWAITING booking may now
--    overlap another counted commitment of the same customer; every other pair of counted commitments is refused
--    exactly as before, including the AWAITING -> CONFIRMED move of an Accept (the row enters the predicate again).
--
-- The narrowed predicate only removes rows from the constraint, so every existing row satisfies it and no backfill is
-- needed. Two rules the constraint no longer covers are the application's, under the iam.user_commitment_guard lock
-- that tournament.begin_command takes for the customer:
--   - FR-BOK-04 (O1 condition 4): a new Online Booking overlapping an AWAITING one is still refused - the creation
--     predicate checks every counted commitment, AWAITING included (booking/internal OnlineCreationPredicate).
--   - FR-LCF-15 (D1): Accept checks the customer's overlap itself, records LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT and
--     answers 409 LATE_CONFIRM_CUSTOMER_CONFLICT, without relying on a 23P01 (E7-58).
--
-- Unchanged on purpose: booking.derive_commitment_period and tr_00_booking_commitment_period (AWAITING keeps its
-- commitment_period), ck_booking_commitment_period_shape, the 4-commitment count (FR-BOK-03, FR-LCF-05), every other
-- table, column, index, constraint, trigger, function, grant and data row. The guard identifier mapping of
-- ex_customer_no_overlap (23P01 -> OVERLAPPING_COMMITMENT) is unchanged.
-- Engine-lane constraint (Booking): needs the Engine owner's review.
-- =====================================================================================================
ALTER TABLE booking.booking
    DROP CONSTRAINT ex_customer_no_overlap;

ALTER TABLE booking.booking
    ADD CONSTRAINT ex_customer_no_overlap
    EXCLUDE USING gist (
        customer_id WITH =,
        commitment_period WITH &&
    )
    WHERE (commitment_period IS NOT NULL AND status <> 'AWAITING_CUSTOMER_CONFIRMATION');

COMMENT ON CONSTRAINT ex_customer_no_overlap ON booking.booking IS E'FR-BOK-04: no two counted commitments of one Customer overlap. AWAITING_CUSTOMER_CONFIRMATION is exempt (v1.1.35, E7-58 D1; FR-LCF-15/16): the Late Confirm may wait despite a schedule conflict, Accept checks it, and a new Online Booking overlapping an AWAITING one is refused by the creation predicate under the commitment guard.';
