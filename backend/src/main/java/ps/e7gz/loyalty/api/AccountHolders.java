package ps.e7gz.loyalty.api;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: who holds a points and wallet account, as the Platform Admin's loyalty pages show them (FR-ADM-09) and
 * find them (FR-ADM-04). Every Customer holds both accounts from registration ({@code loyalty.initialize_user}).
 *
 * <p>Implemented by identity, which owns {@code iam.user_account}. It is declared here because identity reads loyalty
 * for the admin user page (E7-66 PR 2, PO decision D4), so loyalty cannot depend on identity (CLAUDE.md section 3, no
 * module cycles).
 */
public interface AccountHolders {

    /** The Customers among {@code userIds}; an id that is not a Customer is left out. */
    Map<UUID, Holder> find(Collection<UUID> userIds);

    /** One Customer, on the command's own transaction (after its locks); empty when it is not a Customer. */
    Optional<Holder> find(CommandContext command, UUID userId);

    /**
     * The Customers {@code q} names (Sprint 4 contract, E7-65): part of the full name, username or email, or the exact
     * phone in any form registration accepts; trimmed and case-insensitive. A blank {@code q} names nobody.
     */
    Set<UUID> search(String q);

    /**
     * @param phone   E.164; {@code null} for an anonymised account, whose phone is no longer a phone number (FR-ACC-10)
     * @param deleted anonymised (FR-ACC-10): its name and email are already empty
     */
    record Holder(UUID userId, String fullName, String username, String phone, boolean deleted) {

        public Holder {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(fullName, "fullName");
            Objects.requireNonNull(username, "username");
        }
    }
}
