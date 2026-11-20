package ps.e7gz.attendance.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.VenueAccountRestrictionsAt;
import ps.e7gz.common.command.CommandContext;

/**
 * {@link VenueAccountRestrictionsAt} on {@code iam.venue_account_restriction_interval}, the immutable history this
 * module owns (arch 03 section 1, module 11). E7-78 opens a row on every disable and closes it once on the re-enable.
 */
@Component
class JdbcVenueAccountRestrictionsAt implements VenueAccountRestrictionsAt {

    static final String DISABLED_AT = """
            SELECT EXISTS (
                SELECT 1
                  FROM iam.venue_account_restriction_interval r
                 WHERE r.venue_id = :venueId
                   AND r.disabled_at <= :at
                   AND (r.reenabled_at IS NULL OR r.reenabled_at > :at))
            """;

    @Override
    public boolean disabledAt(CommandContext command, UUID venueId, Instant at) {
        return Boolean.TRUE.equals(command.jdbc().sql(DISABLED_AT)
                .param("venueId", venueId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(Boolean.class).single());
    }
}
