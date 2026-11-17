package ps.e7gz.trust.internal;

import java.time.Period;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.util.PGInterval;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The no-show ladder (FR-TRS-13, arch 07 section 5.1), read from the very functions every Ban uses -
 * {@code trust.penalty_threshold(stage)} and {@code trust.penalty_duration(stage)} - so the Platform Admin's Global
 * Rules page (D7, PO decision 20 Oct 2026) can never show a different rule than the one enforced. The ladder lives only
 * in the database (E7-69, accepted by the PO on 15 Nov 2026); there is no configuration and no write (NFR-37, A8).
 *
 * <p>Stage {@code s} (0..3) is where the Customer stands; its threshold of consecutive no-shows starts a Ban of the next
 * stage's duration ({@code trust.penalty_duration(LEAST(s + 1, 3))}): 0 - 3 - P7D, 1 - 2 - P1M, 2 - 2 - P2M, and stage 3
 * recurs - 3 - P2M.
 */
@Component
public class PenaltyLadder {

    /** The last stage of the ladder: from it on, every threshold crossing gives the same Ban (FR-TRS-13). */
    static final int RECURRING_STAGE = 3;

    static final String LADDER = """
            SELECT s AS stage, trust.penalty_threshold(CAST(s AS smallint)) AS threshold,
                   trust.penalty_duration(CAST(LEAST(s + 1, 3) AS smallint)) AS duration
              FROM generate_series(0, 3) AS s ORDER BY s
            """;

    private final JdbcClient jdbc;

    PenaltyLadder(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** One rung per stage, in order; the last one recurs. */
    public List<Step> steps() {
        return jdbc.sql(LADDER).query((rs, n) -> {
            int stage = rs.getInt("stage");
            return new Step(stage, rs.getInt("threshold"), period((PGInterval) rs.getObject("duration")),
                    stage == RECURRING_STAGE);
        }).list();
    }

    /** A calendar interval as its ISO-8601 period (P7D, P1M, P2M); the ladder has no time-of-day part. */
    static Period period(PGInterval interval) {
        if (interval.getHours() != 0 || interval.getMinutes() != 0 || interval.getSeconds() != 0) {
            throw new IllegalStateException("A Ban duration is whole days or months: " + interval.getValue());
        }
        return Period.of(interval.getYears(), interval.getMonths(), interval.getDays());
    }

    /**
     * @param consecutiveNoShows the stage's threshold: eligible no-shows in a row that start the Ban (FR-TRS-14)
     * @param banDuration        the Ban it starts
     * @param recurring          the last rung, repeated for every later crossing
     */
    public record Step(int penaltyStage, int consecutiveNoShows, Period banDuration, boolean recurring) {
    }
}
