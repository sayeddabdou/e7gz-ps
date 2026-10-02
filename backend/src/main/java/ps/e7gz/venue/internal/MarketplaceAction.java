package ps.e7gz.venue.internal;

import ps.e7gz.common.codes.MarketplaceStatus;

/**
 * The Platform Admin's marketplace status actions (arch 09 section 5, FR-VEN-07). Product Owner decisions: completing
 * onboarding moves ONBOARDING to INACTIVE, never ACTIVE (28 Sep 2026); while a venue is ONBOARDING, completing
 * onboarding is its only status action (2 Oct 2026, E7-23). The database separately refuses any return to ONBOARDING
 * ({@code venue.guard_venue_marketplace_transition}) and ACTIVE / SUSPENDED in an inactive area
 * ({@code gd_venue_area_inactive_status}).
 */
public enum MarketplaceAction {

    COMPLETE_ONBOARDING(MarketplaceStatus.INACTIVE),
    ACTIVATE(MarketplaceStatus.ACTIVE),
    DEACTIVATE(MarketplaceStatus.INACTIVE),
    SUSPEND(MarketplaceStatus.SUSPENDED);

    private final MarketplaceStatus target;

    MarketplaceAction(MarketplaceStatus target) {
        this.target = target;
    }

    public MarketplaceStatus target() {
        return target;
    }

    /** Whether this action may run on a venue in {@code current} ({@code MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED}). */
    boolean allowedFrom(MarketplaceStatus current) {
        if (this == COMPLETE_ONBOARDING) {
            return current == MarketplaceStatus.ONBOARDING;
        }
        return current != MarketplaceStatus.ONBOARDING && current != target;
    }
}
