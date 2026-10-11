package ps.e7gz.identity.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: a Customer's username and phone, for the venue's view of an Online Booking (E7-47, arch 09 section 4.1).
 * The phone is only ever shown through the booking module's counterpart visibility rule (FR-DIS-09, FR-PTS-20, arch
 * 10 section 5.1); this read does not decide it.
 */
public interface CustomerContacts {

    /** On the command's own transaction; empty when the User does not exist. */
    Optional<Contact> find(CommandContext command, UUID userId);

    /**
     * The Customer whose current username equals {@code username} exactly, after the normalisation of username login -
     * trimmed and case-insensitive, as {@code ux_user_username_normalized} - on the command's own transaction. Never a
     * partial match, never a phone, never an anonymised account (FR-ACC-10); empty for a blank {@code username}. One
     * indexed read. Added for the venue's booking search (PO decision 11 Oct 2026, E7-49, D3 amendment).
     */
    Optional<UUID> findByUsername(CommandContext command, String username);

    /**
     * @param username the stored username (an anonymised account keeps its row and username, FR-ACC-10)
     * @param phone    E.164; {@code null} for an anonymised account, whose phone is no longer a phone number
     */
    record Contact(UUID userId, String username, String phone) {

        public Contact {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(username, "username");
        }
    }
}
