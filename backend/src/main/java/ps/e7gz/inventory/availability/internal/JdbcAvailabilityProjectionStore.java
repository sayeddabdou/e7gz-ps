package ps.e7gz.inventory.availability.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection.Row;

/**
 * {@code inventory.venue_activity_availability}, which this module owns, on the refresh command's transaction. A write
 * never replaces a row refreshed later than itself, so a slow refresh that started first cannot overwrite a newer one.
 */
@Component
class JdbcAvailabilityProjectionStore {

    static final String UPSERT = """
            INSERT INTO inventory.venue_activity_availability AS p (venue_id, activity, nearest_available_at,
                                                                    starting_price, refreshed_at)
            VALUES (:venueId, :activity, :nearestAvailableAt, :startingPrice, :refreshedAt)
            ON CONFLICT (venue_id, activity) DO UPDATE
               SET nearest_available_at = EXCLUDED.nearest_available_at,
                   starting_price = EXCLUDED.starting_price,
                   refreshed_at = EXCLUDED.refreshed_at
             WHERE p.refreshed_at <= EXCLUDED.refreshed_at
            """;

    static final String REMOVE = """
            DELETE FROM inventory.venue_activity_availability
             WHERE venue_id = :venueId AND activity = :activity AND refreshed_at <= :refreshedAt
            """;

    static final String FIND = """
            SELECT nearest_available_at, starting_price, refreshed_at
              FROM inventory.venue_activity_availability
             WHERE venue_id = :venueId AND activity = :activity
            """;

    void write(CommandContext command, UUID venueId, Activity activity, Instant nearestAvailableAt,
               Money startingPrice, Instant refreshedAt) {
        command.jdbc().sql(UPSERT)
                .param("venueId", venueId)
                .param("activity", activity.name())
                .param("nearestAvailableAt", nearestAvailableAt == null ? null : utc(nearestAvailableAt))
                .param("startingPrice", startingPrice == null ? null : startingPrice.amount())
                .param("refreshedAt", utc(refreshedAt))
                .update();
    }

    void remove(CommandContext command, UUID venueId, Activity activity, Instant refreshedAt) {
        command.jdbc().sql(REMOVE)
                .param("venueId", venueId)
                .param("activity", activity.name())
                .param("refreshedAt", utc(refreshedAt))
                .update();
    }

    Optional<Row> find(CommandContext command, UUID venueId, Activity activity) {
        return command.jdbc().sql(FIND)
                .param("venueId", venueId)
                .param("activity", activity.name())
                .query((rs, n) -> {
                    OffsetDateTime nearest = rs.getObject("nearest_available_at", OffsetDateTime.class);
                    BigDecimal price = rs.getBigDecimal("starting_price");
                    return new Row(venueId, activity, nearest == null ? null : nearest.toInstant(),
                            price == null ? null : Money.of(price),
                            rs.getObject("refreshed_at", OffsetDateTime.class).toInstant());
                })
                .optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
