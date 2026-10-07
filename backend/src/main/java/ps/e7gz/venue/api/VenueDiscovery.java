package ps.e7gz.venue.api;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.geo.Coordinates;

/**
 * Read-only: venues as customer discovery shows them (arch 09 section 3.2, FR-DIS-01…FR-DIS-03, FR-DIS-06, FR-DIS-08,
 * FR-DIS-11; E7-34). Distance and order are computed by PostGIS on {@code venue.venue.location} (ADR-019), never in
 * Java. The venue phone is not part of any record here (FR-DIS-09).
 */
public interface VenueDiscovery {

    /**
     * One page of marketplace-ACTIVE venues offering {@code activity} (FR-DIS-08), in a stable order:
     * <ul>
     *   <li>with {@code near}: nearest first by the KNN operator {@code location <-> near}, then id (ADR-019);</li>
     *   <li>without it: by name, then id (PO decision 7 Oct 2026).</li>
     * </ul>
     * {@code areaId}, when set, is a hard filter in both orders (FR-DIS-02). {@code after} is the last venue of the
     * previous page (keyset paging, arch 09 section 1.2); an id that names no venue is VALIDATION_FAILED on
     * {@code cursor}.
     */
    Page search(Search search);

    /** Any venue in any marketplace status (FR-DIS-08: a deep link still opens it); empty when there is none. */
    Optional<Profile> profile(UUID venueId, Coordinates from);

    /**
     * The venue's weekly opening hours in venue local time, weekday 0 = Monday … 6 = Sunday (SPRINT-1-CONTRACT section
     * 2), all seven. Before hours were ever saved every day is closed: the venue has no Operating Session.
     */
    List<OpeningDay> weeklyHours(UUID venueId);

    /**
     * Whether a materialized Operating Session ({@code venue.operating_session}, ADR-009) contains {@code at}, and
     * otherwise when the next one starts (FR-DIS-06 Closed Now / Opens at). The sessions are the authority, as for the
     * business date.
     */
    OpenState openState(UUID venueId, Instant at);

    /** {@code near} null orders by name; {@code after} null is the first page. */
    record Search(UUID areaId, Activity activity, Coordinates near, UUID after, int limit) {

        public Search {
            Objects.requireNonNull(activity, "activity");
            if (limit < 1) {
                throw new IllegalArgumentException("limit");
            }
        }
    }

    /** {@code more}: another page follows the last item. */
    record Page(List<Listed> items, boolean more) {
    }

    /** {@code distanceMeters} is null without a customer location. */
    record Listed(UUID venueId, String name, UUID areaId, Integer distanceMeters) {
    }

    /**
     * {@code activities}: the activities the venue offers ({@code venue.venue_activity}), in the {@code Activity} enum's
     * order; {@code distanceMeters} null without a customer location.
     */
    record Profile(UUID venueId, String name, UUID areaId, String address, Coordinates location,
                   MarketplaceStatus marketplaceStatus, List<Activity> activities, Integer distanceMeters) {
    }

    /** A closed day has no times; a close at or before the open ends on the next day (FR-VEN-17). */
    record OpeningDay(int weekday, boolean closed, LocalTime opensAtLocal, LocalTime closesAtLocal) {
    }

    /** {@code opensAt} is null while open, and when no later session is materialized. */
    record OpenState(boolean openNow, Instant opensAt) {
    }
}
