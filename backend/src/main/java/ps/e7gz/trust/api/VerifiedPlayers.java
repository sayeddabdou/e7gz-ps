package ps.e7gz.trust.api;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.codes.VerifiedPlayerStatus;

/**
 * Read-only: a Customer's Verified Player (FR-ACC-11) - the qualification derived from corrected trust history, its
 * progress in completed attended bookings, and the separate operational status. Added for the Platform Admin's user
 * pages (E7-66, PO decision D4, 17 Oct 2026; FR-ADM-04); the customer's own Account Standing (E7-72) can read the same.
 *
 * <p>From the trust projection ({@code trust.trust_projection}). A Customer without a trust event has no projection
 * row yet: no completed booking, NOT_QUALIFIED and NOT_VERIFIED (the status {@code trust.refresh_projection} starts
 * from).
 */
public interface VerifiedPlayers {

    /** Each of {@code userIds} (Customers the caller found): one entry per distinct id. */
    Map<UUID, VerifiedPlayer> of(Collection<UUID> userIds);

    record VerifiedPlayer(UUID userId, VerifiedPlayerQualification qualification, int completedBookings,
                          VerifiedPlayerStatus status) {

        public VerifiedPlayer {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(qualification, "qualification");
            Objects.requireNonNull(status, "status");
        }
    }
}
