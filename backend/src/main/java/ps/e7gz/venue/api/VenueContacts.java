package ps.e7gz.venue.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: a Venue's name and phone, for the customer booking detail returned on creation (E7-46, arch 09 section
 * 3.3). The phone is only ever shown through the booking module's counterpart visibility rule (FR-DIS-09, FR-PTS-20,
 * arch 10 section 5.1); this read does not decide it.
 */
public interface VenueContacts {

    /** On the command's own transaction; empty when the Venue does not exist. */
    Optional<Contact> find(CommandContext command, UUID venueId);

    /** @param phone E.164 ({@code venue.venue.phone}, NOT NULL) */
    record Contact(UUID venueId, String name, String phone) {

        public Contact {
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(phone, "phone");
        }
    }
}
