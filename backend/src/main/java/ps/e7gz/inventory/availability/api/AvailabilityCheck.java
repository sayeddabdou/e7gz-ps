package ps.e7gz.inventory.availability.api;

/**
 * The eight checks of the canonical availability predicate, in their frozen order ({@code FR-AVL-02}, arch 05
 * section 2.1). The order is not an optimisation choice: the first failing check is the reason a Resource is not free,
 * and its {@link #index()} is what callers report.
 *
 * <p>The index is an explicit field, never {@link #ordinal()}: reordering or inserting a constant must not change what
 * an index means.
 */
public enum AvailabilityCheck {

    /** 1 - the Venue has an operational selling block (a state, not an interval). {@code FR-AVL-07} */
    VENUE_SELLING_BLOCK(1),
    /** 2 - the Resource has an operational selling block (a state, not an interval). {@code FR-AVL-07} */
    RESOURCE_SELLING_BLOCK(2),
    /** 3 - the Resource base state is not {@code ACTIVE}. {@code FR-AVL-06} */
    RESOURCE_BASE_STATE(3),
    /** 4 - the Resource operational restriction is not {@code NONE} (owner review). {@code FR-VEN-13} */
    RESOURCE_OWNER_REVIEW(4),
    /** 5 - a relevant Emergency's declared interval overlaps the period. {@code FR-OPS-08} */
    EMERGENCY_INTERVAL(5),
    /** 6 - Maintenance on the Resource overlaps the period. */
    MAINTENANCE(6),
    /** 7 - a Closure of the Resource's Venue overlaps the period. */
    CLOSURE(7),
    /** 8 - an active blocking reservation on the Resource overlaps the period. {@code FR-AVL-03} */
    BLOCKING_RESERVATION(8);

    private final int index;

    AvailabilityCheck(int index) {
        this.index = index;
    }

    /** The frozen 1-based position of this check in {@code FR-AVL-02}. */
    public int index() {
        return index;
    }
}
