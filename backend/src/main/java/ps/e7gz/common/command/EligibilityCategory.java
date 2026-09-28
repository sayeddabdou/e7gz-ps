package ps.e7gz.common.command;

/**
 * The eligibility category every command declares up front (ADR-022, FRS section 2.3). The category, not the actor, decides
 * which venue-eligibility rule applies; the category-to-rule mapping itself is not part of this type.
 */
public enum EligibilityCategory {

    /** A - New-Activity Marketplace. */
    A_NEW_ACTIVITY_MARKETPLACE,

    /** B - Existing-Commitment Operational. */
    B_EXISTING_COMMITMENT_OPERATIONAL,

    /** C - Internal Venue Operation. */
    C_INTERNAL_VENUE_OPERATION
}
