package ps.e7gz.trust.api;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The Customers Trust's admin pages name (E7-72: the Verified Players list and the moderation answers, Spec v5 section
 * 51.5). Implemented by Identity, which already depends on Trust: the port lives here so Trust never depends on Identity
 * (no module cycle, CLAUDE.md section 3).
 */
public interface TrustCustomers {

    /** Each of {@code userIds} that is a Customer; unknown ids are absent. */
    Map<UUID, Customer> of(Collection<UUID> userIds);

    /**
     * @param deleted anonymised (FR-ACC-10): its name is blank and no admin action applies to it any more
     */
    record Customer(UUID userId, String fullName, String username, boolean deleted) {

        public Customer {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(fullName, "fullName");
            Objects.requireNonNull(username, "username");
        }
    }
}
