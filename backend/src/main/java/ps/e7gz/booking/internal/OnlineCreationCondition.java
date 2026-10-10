package ps.e7gz.booking.internal;

import ps.e7gz.common.error.ApiErrorCode;

/**
 * The twenty cumulative conditions of a new Online Booking - one ordered, named list (arch 06 §1.3, FRS FR-BOK-11,
 * Product v5.0 §22; risk I2). {@link #number()} is the arch 06 / FR-BOK-11 numbering (PO decision 9 Oct 2026);
 * {@link #specNumber()} is the same condition's number in Product v5.0 §22, which lists them in another order. The two
 * lists hold the same twenty conditions and §22 requires all of them, so which code answers when several fail at once
 * is not a product rule (PO decision 9 Oct 2026). {@code OnlineCreationConditionTest} asserts there are exactly twenty.
 *
 * <p><b>No twenty-first condition.</b> A venue without a price for the activity fails under {@link #OTHER_PRODUCT_VALIDATION}
 * (§22 item 20, PO decision 5 Oct 2026). The explicit "required pricing configuration exists" condition belongs to the
 * manual predicate (FR-MAN-02 item 15); adding it here would be a Product Change Request against v5.0 §22, and the
 * Product Owner has ruled that none is added.
 *
 * <p>Codes: the error mapping of the PO decision of 9 Oct 2026 (BUILD-PLAN E7-46, arch 09 §1.5). Condition 1 may also
 * answer {@code ACCOUNT_DELETED} for an anonymised account (FR-ACC-10), as {@code CustomerActivation} decides.
 */
enum OnlineCreationCondition {

    // Customer
    /** Account Verified by Phone OTP - FR-ACC-03. */
    PHONE_VERIFIED(1, 1, ApiErrorCode.PHONE_VERIFICATION_REQUIRED),
    /** Customer not banned - FR-ACC-17. */
    NOT_BANNED(2, 2, ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION),
    /** Below the maximum of 4 active/upcoming Online commitments - FR-BOK-03. */
    BELOW_COMMITMENT_LIMIT(3, 3, ApiErrorCode.COMMITMENT_LIMIT_REACHED),
    /** No overlapping Customer Online commitment - FR-BOK-04. */
    NO_OVERLAPPING_COMMITMENT(4, 4, ApiErrorCode.OVERLAPPING_COMMITMENT),

    // Venue
    /** Venue Marketplace Status ACTIVE (category A) - FR-VEN-06. */
    VENUE_MARKETPLACE_ACTIVE(5, 5, ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY),
    /** Venue Account ENABLED - FR-VEN-08. */
    VENUE_ACCOUNT_ENABLED(6, 6, ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY),
    /** Area ACTIVE - FR-VEN-12. */
    AREA_ACTIVE(7, 7, ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY),
    /** Venue currently inside an Operating Session - FR-BOK-08, §21. */
    VENUE_CURRENTLY_OPEN(8, 8, ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED),
    /** Now at least 10 minutes before the current Operating Session ends - FR-BOK-09, §21. */
    BEFORE_INTAKE_CUTOFF(9, 9, ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED),
    /** No Venue Operational Selling Block - FR-AVL-07 (availability check 1). */
    NO_VENUE_SELLING_BLOCK(10, 10, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE),
    /** No Resource Operational Selling Block - FR-AVL-07 (availability check 2). */
    NO_RESOURCE_SELLING_BLOCK(11, 17, ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE),

    // Timing
    /** Requested start at least 15 minutes ahead: {@code now <= startAt - 15 min} - FR-BOK-02. */
    LEAD_TIME(12, 11, ApiErrorCode.BOOKING_LEAD_TIME_TOO_SHORT),
    /** Duration between 1 and 6 hours - FR-BOK-01. */
    DURATION_RANGE(13, 12, ApiErrorCode.BOOKING_DURATION_INVALID),
    /** Duration in 30-minute increments - FR-BOK-01. */
    DURATION_INCREMENT(14, 13, ApiErrorCode.BOOKING_DURATION_INVALID),
    /** Interval fits entirely inside one future Operating Session - FR-BOK-05. */
    WITHIN_ONE_SESSION(15, 14, ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION),

    // Resource
    /** Resource Base State ACTIVE - FR-AVL-06 (availability check 3). */
    RESOURCE_ACTIVE(16, 15, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
    /** Resource Operational Restriction NONE - FR-VEN-13 (availability check 4). */
    RESOURCE_NOT_UNDER_OWNER_REVIEW(17, 16, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
    /**
     * No overlapping blocking Resource reservation - FR-AVL-03, FR-AVL-11. Availability checks 5-8: a declared
     * emergency interval, Maintenance and a Closure count as blocking reservations here (Product v5.0 §16.3; PO
     * decision 9 Oct 2026).
     */
    NO_BLOCKING_RESERVATION(18, 18, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE),
    /** Activity and Resource configuration compatible: a play mode exactly for PlayStation - FR-VEN-42. */
    ACTIVITY_COMPATIBLE(19, 19, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
    /** All other required Product validation, including a price for the activity (FR-VEN-45; PO decision 5 Oct 2026). */
    OTHER_PRODUCT_VALIDATION(20, 20, ApiErrorCode.RESOURCE_NOT_ELIGIBLE);

    private final int number;
    private final int specNumber;
    private final ApiErrorCode code;

    OnlineCreationCondition(int number, int specNumber, ApiErrorCode code) {
        this.number = number;
        this.specNumber = specNumber;
        this.code = code;
    }

    /** The arch 06 §1.3 / FR-BOK-11 number - an explicit field, never {@link #ordinal()}. */
    int number() {
        return number;
    }

    /** The number of the same condition in Product v5.0 §22. */
    int specNumber() {
        return specNumber;
    }

    /** The public error code a failure of this condition answers with (PO decision 9 Oct 2026). */
    ApiErrorCode code() {
        return code;
    }
}
