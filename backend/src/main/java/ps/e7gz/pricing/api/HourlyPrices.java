package ps.e7gz.pricing.api;

import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;

/**
 * The hourly price of a Resource now (FR-VEN-43…FR-VEN-45): a PlayStation room's override for its current console and
 * the play mode, else the venue default for that console and play mode; Billiards and Ping Pong the venue rate, with
 * no play mode. Online, manual and tournament pricing all read it, so they price from the same configuration.
 */
public interface HourlyPrices {

    /**
     * On the command's own transaction, so the price is the one in force when the booking commits. Empty when the
     * configuration the activity needs does not exist (FR-VEN-45: creation is then blocked by the caller), when the
     * Resource does not exist, or when the play mode does not fit the activity (required for PlayStation, absent
     * otherwise, FR-VEN-42).
     */
    Optional<Money> resolve(CommandContext command, UUID resourceId, PlayMode playMode);
}
