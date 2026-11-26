package ps.e7gz.tournament.internal;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
import java.util.UUID;
import org.postgresql.Driver;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;

/**
 * TEST-ONLY: Tournaments for other modules' tests (E7-93) - a fresh Venue's open Tournament and a Customer's Join,
 * through the real commands, and the participant row as stored.
 */
public final class TournamentFlows {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private final TournamentCreation creation;
    private final TournamentParticipation participation;

    private TournamentFlows(ApplicationContext context) {
        this.creation = context.getBean(TournamentCreation.class);
        this.participation = context.getBean(TournamentParticipation.class);
    }

    public static TournamentFlows of(ApplicationContext context) {
        return new TournamentFlows(context);
    }

    /** A Venue-hosted Tournament at a fresh Venue, WAITING_FOR_PLAYERS, starting two days from now (16 places). */
    public UUID openTournament() {
        World world = World.create();
        return creation.createByVenue(world.account().id(), TournamentRequest.of(Activity.PLAYSTATION,
                PlayMode.SINGLE, world.rooms(), 16, world.startAt(), 120, TournamentVisibility.PUBLIC, null,
                null)).id();
    }

    /** As {@link #openTournament()}, starting {@code lead} from now. */
    public UUID openTournament(Duration lead) {
        World world = World.create(lead);
        return creation.createByVenue(world.account().id(), TournamentRequest.of(Activity.PLAYSTATION,
                PlayMode.SINGLE, world.rooms(), 16, world.startAt(), 120, TournamentVisibility.PUBLIC, null,
                null)).id();
    }

    public void join(UUID customerId, UUID tournamentId) {
        participation.join(customerId, tournamentId);
    }

    /** A private Tournament's invite code. */
    public static String inviteCode(UUID tournamentId) {
        return OWNER.sql("SELECT invite_code FROM tournament.tournament WHERE id = :id").param("id", tournamentId)
                .query(String.class).single();
    }

    /** {@code STATUS:CAUSE} of the Customer's row, the cause empty unless INELIGIBLE. */
    public static String participant(UUID tournamentId, UUID userId) {
        return OWNER.sql("""
                        SELECT status || ':' || COALESCE(ineligible_reason, '') FROM tournament.tournament_participant
                         WHERE tournament_id = :t AND user_id = :u""")
                .param("t", tournamentId).param("u", userId).query(String.class).single();
    }
}
