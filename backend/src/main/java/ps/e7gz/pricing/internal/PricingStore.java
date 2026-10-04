package ps.e7gz.pricing.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.money.Money;
import ps.e7gz.pricing.internal.Pricing.DefaultPrice;
import ps.e7gz.pricing.internal.Pricing.RoomOverride;

/** The SQL on {@code pricing.venue_default_price} and {@code pricing.resource_price_override}. */
final class PricingStore {

    /** PlayStation first, then console and play mode in their declared order (FR-VEN-43); nulls first. */
    static final Comparator<DefaultPrice> DEFAULT_ORDER = Comparator
            .comparing(DefaultPrice::activity)
            .thenComparing(DefaultPrice::consoleType, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(DefaultPrice::playMode, Comparator.nullsFirst(Comparator.naturalOrder()));

    private PricingStore() {
    }

    static List<DefaultPrice> defaults(JdbcClient jdbc, UUID venueId) {
        return jdbc.sql("""
                        SELECT activity, console_type, play_mode, hourly_price
                          FROM pricing.venue_default_price WHERE venue_id = :venue""")
                .param("venue", venueId).query(PricingStore::mapDefault).list()
                .stream().sorted(DEFAULT_ORDER).toList();
    }

    static List<RoomOverride> overrides(JdbcClient jdbc, Collection<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT resource_id, console_type, play_mode, hourly_price
                          FROM pricing.resource_price_override WHERE resource_id IN (:resources)""")
                .param("resources", resourceIds).query(PricingStore::mapOverride).list();
    }

    /**
     * Sets each price sent, adding the ones the venue does not have yet (ux_default_price). Default prices are never
     * deleted: the runtime role has no DELETE on the table (R__security_baseline, "every other table is history").
     */
    static void upsertDefaults(JdbcClient jdbc, UUID venueId, List<DefaultPrice> defaults) {
        for (DefaultPrice price : defaults) {
            jdbc.sql("""
                            INSERT INTO pricing.venue_default_price (id, venue_id, activity, console_type, play_mode,
                                                                     hourly_price)
                            VALUES (:id, :venue, :activity, :console, :mode, :price)
                            ON CONFLICT ON CONSTRAINT ux_default_price
                            DO UPDATE SET hourly_price = EXCLUDED.hourly_price""")
                    .param("id", UUID.randomUUID()).param("venue", venueId).param("activity", price.activity().name())
                    .param("console", name(price.consoleType())).param("mode", name(price.playMode()))
                    .param("price", price.hourlyPrice().amount()).update();
        }
    }

    static void replaceOverrides(JdbcClient jdbc, UUID resourceId, ConsoleType console,
                                 List<RoomOverride> overrides) {
        jdbc.sql("DELETE FROM pricing.resource_price_override WHERE resource_id = :resource")
                .param("resource", resourceId).update();
        for (RoomOverride price : overrides) {
            jdbc.sql("""
                            INSERT INTO pricing.resource_price_override (id, resource_id, console_type, play_mode,
                                                                         hourly_price)
                            VALUES (:id, :resource, :console, :mode, :price)""")
                    .param("id", UUID.randomUUID()).param("resource", resourceId).param("console", console.name())
                    .param("mode", price.playMode().name()).param("price", price.hourlyPrice().amount()).update();
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static DefaultPrice mapDefault(ResultSet rs, int row) throws SQLException {
        String console = rs.getString("console_type");
        String mode = rs.getString("play_mode");
        return new DefaultPrice(Activity.valueOf(rs.getString("activity")),
                console == null ? null : ConsoleType.valueOf(console), mode == null ? null : PlayMode.valueOf(mode),
                Money.of(rs.getBigDecimal("hourly_price")));
    }

    private static RoomOverride mapOverride(ResultSet rs, int row) throws SQLException {
        return new RoomOverride(rs.getObject("resource_id", UUID.class),
                ConsoleType.valueOf(rs.getString("console_type")), PlayMode.valueOf(rs.getString("play_mode")),
                Money.of(rs.getBigDecimal("hourly_price")));
    }
}
