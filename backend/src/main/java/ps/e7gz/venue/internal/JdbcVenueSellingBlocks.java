package ps.e7gz.venue.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.VenueSellingBlocks;

/** {@code venue.venue_selling_block} on the command's transaction ({@link CommandContext#jdbc()}). */
@Component
class JdbcVenueSellingBlocks implements VenueSellingBlocks {

    /** {@code ck_venue_block_state}: an active row has no {@code ended_at}; an ended row has one. */
    static final String BLOCKED_AT = """
            SELECT EXISTS (
                SELECT 1
                  FROM venue.venue_selling_block b
                 WHERE b.venue_id = :venueId
                   AND b.started_at <= :at
                   AND (b.active OR b.ended_at > :at))
            """;

    @Override
    public boolean venueBlocked(CommandContext command, UUID venueId, Instant at) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(at, "at");
        return command.jdbc().sql(BLOCKED_AT)
                .param("venueId", venueId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(Boolean.class).single();
    }
}
