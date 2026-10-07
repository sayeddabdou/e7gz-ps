package ps.e7gz.pricing.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.money.Money;
import ps.e7gz.pricing.api.VenuePriceList;

/** {@code pricing.venue_default_price} of one venue and activity, on the app connection. */
@Component
class JdbcVenuePriceList implements VenuePriceList {

    private final JdbcClient jdbc;

    JdbcVenuePriceList(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public List<HourlyRate> defaults(UUID venueId, Activity activity) {
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(activity, "activity");
        return jdbc.sql("""
                        SELECT console_type, play_mode, hourly_price FROM pricing.venue_default_price
                         WHERE venue_id = :venue AND activity = :activity
                         ORDER BY console_type NULLS FIRST, play_mode NULLS FIRST""")
                .param("venue", venueId).param("activity", activity.name())
                .query((rs, n) -> {
                    String console = rs.getString("console_type");
                    String mode = rs.getString("play_mode");
                    return new HourlyRate(console == null ? null : ConsoleType.valueOf(console),
                            mode == null ? null : PlayMode.valueOf(mode), Money.of(rs.getBigDecimal("hourly_price")));
                })
                .list();
    }
}
