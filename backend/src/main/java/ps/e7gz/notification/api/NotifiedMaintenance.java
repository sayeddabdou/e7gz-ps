package ps.e7gz.notification.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Read-only: what "Maintenance Starting Soon" (Spec v5 section 52.5, FR-NTF-09; E7-80, PO decision 25 Nov 2026 Q4)
 * needs to know about one Maintenance window. Implemented by the operations module; the interface lives here, as
 * {@link NotifiedBookings} does, so this module never depends on the module that owns the window. The reminder
 * re-reads the window when it fires and supersedes itself when the window moved or was deleted, so no operations
 * command has to withdraw it. Runs on the caller's command transaction.
 */
public interface NotifiedMaintenance {

    /** The window as it is now in the caller's transaction, deleted or not; empty when there is no such window. */
    Optional<Window> find(CommandContext command, UUID maintenanceId);

    /**
     * @param venueId the venue of the window's room - the notification's recipient
     * @param deleted the venue deleted it (soft delete, E7-81)
     */
    record Window(UUID maintenanceId, UUID venueId, UUID resourceId, Instant startAt, boolean deleted) {

        public Window {
            Objects.requireNonNull(maintenanceId, "maintenanceId");
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(startAt, "startAt");
        }
    }
}
