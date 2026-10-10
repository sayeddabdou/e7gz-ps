package ps.e7gz.booking.internal;

import ps.e7gz.common.error.ApiErrorCode;

/**
 * The fifteen cumulative conditions of a new Manual Booking - one ordered, named list, numbered as Product v5.0 §30.2
 * lists them (FRS FR-MAN-02, arch 06 §1.4; eligibility category C). Kept apart from the twenty online conditions
 * ({@link OnlineCreationCondition}): the two predicates share no list and no flags. {@code ManualCreationConditionTest}
 * asserts there are exactly fifteen.
 *
 * <p>Codes: PO decision 10 Oct 2026, E7-48 (D4) and the E7-39 contract (PO decision 5 Oct 2026). §30.2 requires all
 * fifteen, so which code answers when several fail at once is not a product rule. Conditions 10 and 11 are one check
 * of the canonical availability predicate (check 5, a declared VENUE- or RESOURCE-scoped emergency interval), which does
 * not say which of the two failed; both answer {@code SLOT_NO_LONGER_AVAILABLE}, so a refusal names condition 10.
 */
enum ManualCreationCondition {

    /** Venue Account ENABLED - §30.2 #1. A disabled account is refused by the access-token filter (401) first. */
    VENUE_ACCOUNT_ENABLED(1, ApiErrorCode.FORBIDDEN),
    /** Marketplace ACTIVE, INACTIVE or SUSPENDED, never ONBOARDING - §30.2 #2, FR-MAN-02 (PO decision 5 Oct 2026). */
    VENUE_NOT_ONBOARDING(2, ApiErrorCode.FORBIDDEN),
    /** Resource Base State ACTIVE - §30.2 #3 (availability check 3). */
    RESOURCE_ACTIVE(3, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
    /** Resource Operational Restriction NONE - §30.2 #4 (availability check 4). */
    RESOURCE_NOT_UNDER_OWNER_REVIEW(4, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
    /** No Venue Operational Selling Block now - §30.2 #5 (availability check 1), as online. */
    NO_VENUE_SELLING_BLOCK(5, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE),
    /** No Resource Operational Selling Block now - §30.2 #6 (availability check 2), as online. */
    NO_RESOURCE_SELLING_BLOCK(6, ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE),
    /** The Resource supports the activity: a play mode exactly for PlayStation (FR-VEN-42) - §30.2 #7. */
    ACTIVITY_SUPPORTED(7, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
    /** No blocking Resource reservation in the interval - §30.2 #8 (availability check 8). */
    NO_BLOCKING_RESERVATION(8, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE),
    /** No overlap with a Venue Closure - §30.2 #9 (availability check 7). */
    NO_CLOSURE(9, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE),
    /** No overlap with an Emergency Closure - §30.2 #10 (availability check 5). */
    NO_EMERGENCY_CLOSURE(10, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE),
    /** No overlap with a Resource Emergency Outage - §30.2 #11 (availability check 5). */
    NO_RESOURCE_EMERGENCY_OUTAGE(11, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE),
    /** No overlap with Maintenance - §30.2 #12 (availability check 6). */
    NO_MAINTENANCE(12, ApiErrorCode.SLOT_NO_LONGER_AVAILABLE),
    /** Start NOW or FUTURE, never retroactive - §30.2 #13, FR-MAN-05. */
    START_NOW_OR_FUTURE(13, ApiErrorCode.MANUAL_BOOKING_BACKDATING_NOT_ALLOWED),
    /** Duration 1-6 hours in 30-minute increments - §30.2 #14. */
    DURATION(14, ApiErrorCode.BOOKING_DURATION_INVALID),
    /** The pricing configuration the activity needs exists - §30.2 #15, FR-MAN-10 (an API error only). */
    PRICING_CONFIGURED(15, ApiErrorCode.MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING);

    private final int number;
    private final ApiErrorCode code;

    ManualCreationCondition(int number, ApiErrorCode code) {
        this.number = number;
        this.code = code;
    }

    /** The Product v5.0 §30.2 number - an explicit field, never {@link #ordinal()}. */
    int number() {
        return number;
    }

    /** The public error code a failure of this condition answers with. */
    ApiErrorCode code() {
        return code;
    }
}
