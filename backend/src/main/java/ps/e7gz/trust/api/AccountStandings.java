package ps.e7gz.trust.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only: a Customer's no-show standing (FR-TRS-11, FR-TRS-13, FR-TRS-15) - the consecutive no-show counter, the
 * penalty stage, the threshold of the next ban at that stage, and the one ban in force. Added for the Platform Admin's
 * user pages (E7-66, PO decision D4, 17 Oct 2026; FR-ADM-04); the customer's own Account Standing (E7-71) can read
 * the same.
 *
 * <p>From the trust projection ({@code trust.trust_projection}, rebuilt from the trust events). A Customer without a
 * trust event has no projection row yet and stands at the start of the ladder: no no-shows, stage 0. The threshold is
 * the database's own ladder ({@code trust.penalty_threshold}). The ban is the Ban Instance in force now - status
 * ACTIVE, {@code activated_at <= now < ends_at}, the same test as {@link ActiveBans} - read against the database clock,
 * outside any command.
 */
public interface AccountStandings {

    /** The standing of each of {@code userIds} (Customers the caller found): one entry per distinct id. */
    Map<UUID, Standing> of(Collection<UUID> userIds);

    /**
     * @param activeBan the ban in force now, or {@code null}
     */
    record Standing(UUID userId, int consecutiveNoShows, int penaltyStage, int currentThreshold, Ban activeBan) {

        public Standing {
            Objects.requireNonNull(userId, "userId");
        }

        public boolean banned() {
            return activeBan != null;
        }
    }

    /** An ACTIVE Ban Instance (FR-TRS-17); its id is what the admin ban routes take. */
    record Ban(UUID banId, int penaltyStage, Instant activatedAt, Instant endsAt) {

        public Ban {
            Objects.requireNonNull(banId, "banId");
            Objects.requireNonNull(activatedAt, "activatedAt");
            Objects.requireNonNull(endsAt, "endsAt");
        }
    }
}
