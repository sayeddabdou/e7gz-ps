package ps.e7gz.operations.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.EmergencyConversionTarget;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.operations.internal.EmergencyCommands.Emergency;
import ps.e7gz.venue.api.EmergencyHoursConversions;

/**
 * {@link EmergencyHoursConversions} (E7-82; the E7-77 contract D5): the venue Emergency an opening-hours commit converts,
 * checked first and converted after the hours are saved - {@link EmergencyCommands#convertedTo}, the very write
 * {@code /convert} ends with. The database checks the target exists at commit: the venue's opening template updated in
 * this transaction ({@code tr_emergency_conversion_target_exists}).
 */
@Component
class JdbcEmergencyHoursConversions implements EmergencyHoursConversions {

    private final VenueEmergencies emergencies;

    JdbcEmergencyHoursConversions(VenueEmergencies emergencies) {
        this.emergencies = emergencies;
    }

    @Override
    public void requireConvertible(CommandContext command, UUID venueId, UUID emergencyId) {
        convertible(command, venueId, emergencyId);
    }

    @Override
    public void convert(CommandContext command, UUID venueId, UUID venueAccountId, UUID emergencyId,
                        UUID correlationId) {
        Emergency emergency = convertible(command, venueId, emergencyId);
        EmergencyCommands.convertedTo(command, emergencies.deps(), emergency,
                EmergencyConversionTarget.OPENING_HOURS_CHANGE, null, venueAccountId, correlationId, null, null);
    }

    private static Emergency convertible(CommandContext command, UUID venueId, UUID emergencyId) {
        Emergency emergency = EmergencyCommands.read(command, venueId, emergencyId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (emergency.scope() != EmergencyScope.VENUE) {
            throw new ApiException(ApiErrorCode.CONVERSION_TARGET_NOT_ALLOWED);
        }
        if (!emergency.unresolved()) {
            throw new ApiException(ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }
        return emergency;
    }
}
