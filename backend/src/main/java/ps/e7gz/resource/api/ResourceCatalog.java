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

    /** The same list on the command's own transaction (E7-33 Nearest Availability). */
    List<Entry> ofVenue(CommandContext command, UUID venueId);

    /**
     * The venue's Resources of one activity that customers see on Venue Details (FR-DIS-11, E7-34), by name then id.
     * DISABLED and ARCHIVED Resources are left out (PO decision 7 Oct 2026); an owner-review restriction or a selling
     * block does not hide a Resource here, it only stops booking it.
     */
    List<Shown> customerVisible(UUID venueId, Activity activity);

    /** A Resource as a customer sees it: its name (FR-VEN-41) and console (PlayStation only). */
    record Shown(UUID resourceId, String name, ConsoleType consoleType) {
        public Shown {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(name, "name");
        }
    }

    /** {@code consoleType} is set exactly for PlayStation ({@code ck_console_iff_playstation}). */
    record Entry(UUID resourceId, UUID venueId, Activity activity, ConsoleType consoleType) {
        public Entry {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(activity, "activity");
        }
    }
}
