package ps.e7gz.venue.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * A venue Emergency converted into an opening-hours change through the hours commit (FR-OPS-23, FR-OPS-24; the E7-77
 * contract D5, PO decision 19 Nov 2026): {@code POST /venue/opening-hours/commit} with the optional {@code emergencyId}
 * converts that Emergency in the same command as the hours. Declared here and implemented by Operations (E7-82), which
 * owns {@code operations.emergency} and already depends on Venue - a port in the caller's {@code api}, no module cycle.
 * Both run on the hours commit's transaction, under its venue lock.
 */
public interface EmergencyHoursConversions {

    /**
     * Before any write: the venue's Emergency exists (else {@code 404 NOT_FOUND}), is a venue Emergency (a resource one
     * is {@code 422 CONVERSION_TARGET_NOT_ALLOWED}: a resource Emergency never becomes an opening-hours change,
     * FR-OPS-23) and is still unresolved (else {@code 409 OPERATIONAL_STATE_CHANGED}, D6).
     */
    void requireConvertible(CommandContext command, UUID venueId, UUID emergencyId);

    /**
     * After the hours are saved in this transaction: the Emergency CONVERTED / OPENING_HOURS_CHANGE and its venue selling
     * block closed at the same instant - no sellable gap (FR-OPS-24); audited and published ({@code EmergencyConverted}).
     */
    void convert(CommandContext command, UUID venueId, UUID venueAccountId, UUID emergencyId, UUID correlationId);
}
