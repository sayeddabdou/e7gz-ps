package ps.e7gz.trust.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.TrustEventType;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.TrustReplay;

/** The sole SQL fold also used by projection validation and rebuild (arch 04 section 4.5).
 * Do not reproduce its correction, protection, adjustment or penalty rules in Java.
 */
@Component
class JdbcTrustReplay implements TrustReplay {
    private static final String STATE = """
            SELECT * FROM trust.replay_state(:user, :at, :sequence)
            """;
    private static final String EFFECTS = """
            SELECT * FROM trust.canonical_trust_effects(:user, :at, :sequence)
            ORDER BY effective_at, event_sequence
            """;
    private static final String TIMELINE = """
            SELECT * FROM trust.replay_timeline(:user, :at, :sequence)
            ORDER BY effective_at, event_sequence
            """;
    private final JdbcClient jdbc;

    JdbcTrustReplay(DataSource appDataSource) {
        jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public State replay(UUID userId, Boundary boundary) {
        return state(jdbc, userId, boundary);
    }

    @Override
    public State replay(CommandContext command, UUID userId, Boundary boundary) {
        return state(command.jdbc(), userId, boundary);
    }

    @Override
    public List<Effect> resolveEffectiveOutcomes(UUID userId, Boundary boundary) {
        return effects(jdbc, userId, boundary);
    }

    @Override
    public List<Effect> resolveEffectiveOutcomes(CommandContext command, UUID userId, Boundary boundary) {
        return effects(command.jdbc(), userId, boundary);
    }

    @Override
    public List<Step> timeline(UUID userId, Boundary boundary) {
        return steps(jdbc, userId, boundary);
    }

    @Override
    public List<Step> timeline(CommandContext command, UUID userId, Boundary boundary) {
        return steps(command.jdbc(), userId, boundary);
    }

    private static State state(JdbcClient jdbc, UUID user, Boundary boundary) {
        return query(jdbc, STATE, user, boundary).query((rs, row) -> new State(
                instant(rs, "last_effective_at"), rs.getObject("last_event_sequence", Long.class),
                rs.getInt("consecutive_no_shows"), rs.getInt("penalty_stage"), rs.getInt("current_threshold"),
                rs.getInt("completed_attended_count"), qualification(rs))).single();
    }

    private static List<Effect> effects(JdbcClient jdbc, UUID user, Boundary boundary) {
        return List.copyOf(query(jdbc, EFFECTS, user, boundary).query((rs, row) -> new Effect(
                rs.getObject("effect_event_id", UUID.class), instant(rs, "effective_at"), rs.getLong("event_sequence"),
                TrustEventType.valueOf(rs.getString("effect_type")), rs.getString("effect_payload"))).list());
    }

    private static List<Step> steps(JdbcClient jdbc, UUID user, Boundary boundary) {
        return List.copyOf(query(jdbc, TIMELINE, user, boundary).query((rs, row) -> new Step(
                rs.getObject("effect_event_id", UUID.class), instant(rs, "effective_at"), rs.getLong("event_sequence"),
                rs.getInt("consecutive_no_shows"), rs.getInt("penalty_stage"), rs.getInt("current_threshold"),
                rs.getInt("completed_attended_count"), qualification(rs),
                rs.getObject("qualification_stage", Integer.class))).list());
    }

    private static JdbcClient.StatementSpec query(JdbcClient jdbc, String sql, UUID user, Boundary boundary) {
        return jdbc.sql(sql).param("user", Objects.requireNonNull(user, "userId"))
                .param("at", boundary == null ? null : OffsetDateTime.ofInstant(boundary.effectiveAt(), ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("sequence", boundary == null ? null : boundary.eventSequence(), Types.BIGINT);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static VerifiedPlayerQualification qualification(ResultSet rs) throws SQLException {
        return VerifiedPlayerQualification.valueOf(rs.getString("vp_qualification"));
    }
}
