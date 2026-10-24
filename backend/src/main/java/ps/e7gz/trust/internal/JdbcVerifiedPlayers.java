package ps.e7gz.trust.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.trust.api.VerifiedPlayers;

/**
 * {@code trust.trust_projection}, read outside any command on the app connection. No projection row yet: no completed
 * booking, NOT_QUALIFIED, NOT_VERIFIED ({@code trust.refresh_projection} starts the status from NOT_VERIFIED).
 */
@Component
class JdbcVerifiedPlayers implements VerifiedPlayers {

    static final String VERIFIED_PLAYERS = """
            SELECT u.id AS user_id,
                   COALESCE(p.vp_qualification, 'NOT_QUALIFIED') AS qualification,
                   COALESCE(p.completed_attended_count, 0) AS completed_bookings,
                   COALESCE(p.vp_operational_status, 'NOT_VERIFIED') AS status
              FROM unnest(CAST(:ids AS uuid[])) AS u(id)
              LEFT JOIN trust.trust_projection p ON p.user_id = u.id
            """;

    private final JdbcClient jdbc;

    JdbcVerifiedPlayers(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public Map<UUID, VerifiedPlayer> of(Collection<UUID> userIds) {
        Objects.requireNonNull(userIds, "userIds");
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, VerifiedPlayer> players = new LinkedHashMap<>();
        jdbc.sql(VERIFIED_PLAYERS)
                .param("ids", userIds.stream().distinct().toArray(UUID[]::new))
                .query((rs, n) -> new VerifiedPlayer(rs.getObject("user_id", UUID.class),
                        VerifiedPlayerQualification.valueOf(rs.getString("qualification")),
                        rs.getInt("completed_bookings"), VerifiedPlayerStatus.valueOf(rs.getString("status"))))
                .list()
                .forEach(player -> players.put(player.userId(), player));
        return players;
    }
}
