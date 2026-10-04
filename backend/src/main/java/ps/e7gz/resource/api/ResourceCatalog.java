package ps.e7gz.resource.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: what a Resource is - its Venue, activity and console - for pricing (FR-VEN-43, FR-VEN-15; E7-27). The
 * SQL stays in the resource module (arch 03; the E7-30 decision on reads from other modules).
 */
public interface ResourceCatalog {

    /** On the command's own transaction; empty when the Resource does not exist. */
    Optional<Entry> find(CommandContext command, UUID resourceId);

    /** Every Resource of the venue, archived ones included, for a read outside a command. */
    List<Entry> ofVenue(UUID venueId);

    /** {@code consoleType} is set exactly for PlayStation ({@code ck_console_iff_playstation}). */
    record Entry(UUID resourceId, UUID venueId, Activity activity, ConsoleType consoleType) {
        public Entry {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(activity, "activity");
        }
    }
}
