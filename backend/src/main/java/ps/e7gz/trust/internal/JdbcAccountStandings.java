package ps.e7gz.trust.internal;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.trust.api.AccountStandings;

/**
 * {@code trust.trust_projection} and {@code trust.ban_instance}, read outside any command on the app connection. No
 * projection row yet: the start of the ladder (counter 0, stage 0, the stage-0 threshold of
 * {@code trust.penalty_threshold}). The ban in force uses {@link JdbcActiveBans}' test against the database clock.
 */
@Component
class JdbcAccountStandings implements AccountStandings {

    static final String STANDINGS = """
            SELECT u.id AS user_id,
                   COALESCE(p.consecutive_no_shows, 0) AS consecutive_no_shows,
                   COALESCE(p.penalty_stage, 0) AS penalty_stage,
                   COALESCE(p.current_threshold, trust.penalty_threshold(CAST(0 AS smallint))) AS current_threshold,
                   b.id AS ban_id, b.penalty_stage AS ban_stage, b.activated_at, b.ends_at
              FROM unnest(CAST(:ids AS uuid[])) AS u(id)
              LEFT JOIN trust.trust_projection p ON p.user_id = u.id
              LEFT JOIN trust.ban_instance b ON b.user_id = u.id AND b.status = 'ACTIVE'
                    AND b.activated_at <= clock_timestamp() AND b.ends_at > clock_timestamp()
            """;

    private final JdbcClient jdbc;

    JdbcAccountStandings(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public Map<UUID, Standing> of(Collection<UUID> userIds) {
        Objects.requireNonNull(userIds, "userIds");
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Standing> standings = new LinkedHashMap<>();
        jdbc.sql(STANDINGS)
                .param("ids", userIds.stream().distinct().toArray(UUID[]::new))
                .query((rs, n) -> {
                    UUID banId = rs.getObject("ban_id", UUID.class);
                    Ban ban = banId == null ? null : new Ban(banId, rs.getInt("ban_stage"),
                            rs.getObject("activated_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("ends_at", OffsetDateTime.class).toInstant());
                    return new Standing(rs.getObject("user_id", UUID.class), rs.getInt("consecutive_no_shows"),
                            rs.getInt("penalty_stage"), rs.getInt("current_threshold"), ban);
                })
                .list()
                .forEach(standing -> standings.put(standing.userId(), standing));
        return standings;
    }
}
