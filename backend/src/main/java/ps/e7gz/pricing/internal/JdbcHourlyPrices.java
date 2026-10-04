package ps.e7gz.pricing.internal;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;
import ps.e7gz.pricing.api.HourlyPrices;
import ps.e7gz.resource.api.ResourceCatalog;

/** The override, else the venue default (FR-VEN-43); Billiards and Ping Pong only the venue rate (FR-VEN-44). */
@Component
class JdbcHourlyPrices implements HourlyPrices {

    private final ResourceCatalog resources;

    JdbcHourlyPrices(ResourceCatalog resources) {
        this.resources = resources;
    }

    @Override
    public Optional<Money> resolve(CommandContext command, UUID resourceId, PlayMode playMode) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resourceId, "resourceId");
        Optional<ResourceCatalog.Entry> found = resources.find(command, resourceId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ResourceCatalog.Entry room = found.get();
        if (room.activity() != Activity.PLAYSTATION) {
            return playMode != null ? Optional.empty() : command.jdbc().sql("""
                            SELECT hourly_price FROM pricing.venue_default_price
                             WHERE venue_id = :venue AND activity = :activity
                               AND console_type IS NULL AND play_mode IS NULL""")
                    .param("venue", room.venueId()).param("activity", room.activity().name())
                    .query(BigDecimal.class).optional().map(Money::of);
        }
        if (playMode == null) {
            return Optional.empty();
        }
        return command.jdbc().sql("""
                        SELECT price FROM (
                            SELECT COALESCE(
                                (SELECT hourly_price FROM pricing.resource_price_override
                                  WHERE resource_id = :resource AND console_type = :console AND play_mode = :mode),
                                (SELECT hourly_price FROM pricing.venue_default_price
                                  WHERE venue_id = :venue AND activity = 'PLAYSTATION'
                                    AND console_type = :console AND play_mode = :mode)) AS price) p
                         WHERE price IS NOT NULL""")
                .param("resource", resourceId).param("venue", room.venueId())
                .param("console", room.consoleType().name()).param("mode", playMode.name())
                .query(BigDecimal.class).optional().map(Money::of);
    }
}
