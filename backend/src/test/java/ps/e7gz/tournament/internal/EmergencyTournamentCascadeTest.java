package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aDefaultPrice;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-82 (S5-E2) — the Tournament part of an Emergency's cascade (arch 06 section 2.5, FR-OPS-08, FR-OPS-12, FR-TRN-27):
 * a venue Emergency cancels the live Tournaments of its window; a resource Emergency cancels a pre-start one using the
 * failed room. The ACTIVE branch (FR-TRN-27: an ACTIVE Tournament loses the room and goes on) is not tested here: no
 * command reaches ACTIVE before the Tournament start of Sprint 6, and planting one means faking its whole checkpoint
 * chain.
 */
@AutoConfigureMockMvc
class EmergencyTournamentCascadeTest extends IntegrationTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final JdbcClient OWNER = JdbcClient.create(new SingleConnectionDataSource(
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true));

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    CommandRunner runner;

    @Autowired
    Tournaments tournaments;

    private CorrelationId.Scope correlation;
    private Fixtures.VenueAccount account;
    private List<UUID> rooms;
    private Instant startAt;
    private UUID sessionId;
    private LocalDate day;
    private Fixtures.Venue venue;

    @BeforeEach
    void aVenueOpenFromNowPastATournament() {
        correlation = CorrelationId.open(CorrelationId.generate());
        venue = aVenue().active().offering(Activity.PLAYSTATION).create();
        account = aVenueAccount().forVenue(venue).create();
        rooms = List.of(aResource().at(venue).playStation(ConsoleType.PS5).create().id(),
                aResource().at(venue).playStation(ConsoleType.PS5).create().id());
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.SINGLE).hourly("100.00").create();
        Instant now = OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
        startAt = now.plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime from = LocalDateTime.ofInstant(now.minus(Duration.ofHours(1)), CAIRO)
                .truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime to = LocalDateTime.ofInstant(startAt.plus(Duration.ofHours(4)), CAIRO);
        day = from.toLocalDate();
        sessionId = anOperatingSession().forVenue(venue).on(day.toString(), from.toString(), to.toString()).create()
                .id();
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void aVenueEmergencyCancelsTheTournamentOfItsWindowAndReleasesItsHolds() throws Exception {
        UUID tournament = venueHosted(rooms);

        declare("/venue-closure", "{\"reason\":\"Power cut\",\"expectedEndAt\":\"" + startAt.plus(Duration.ofHours(1))
                + "\"}");

        assertThat(status(tournament)).isEqualTo("CANCELLED/EMERGENCY_CLOSURE");
        assertThat(activeHolds(tournament)).isZero();
        assertThat(OWNER.sql("SELECT count(*) FROM operations.operational_interruption_record WHERE subject_id = :id"
                + " AND subject_type = 'TOURNAMENT'").param("id", tournament).query(Long.class).single()).isOne();
    }

    @Test
    void aResourceEmergencyCancelsAPreStartTournamentUsingTheFailedRoom() throws Exception {
        UUID tournament = venueHosted(rooms);

        declare("/resource-outage", outage(rooms.getFirst()));

        assertThat(status(tournament)).isEqualTo("CANCELLED/RESOURCE_EMERGENCY_OUTAGE");
        assertThat(activeHolds(tournament)).isZero();
    }

    private String outage(UUID room) {
        return "{\"resourceId\":\"" + room + "\",\"reason\":\"Overheating\",\"expectedReturnAt\":\""
                + startAt.plus(Duration.ofHours(1)) + "\"}";
    }

    private void declare(String path, String body) throws Exception {
        mvc.perform(post("/api/v1/venue/emergencies" + path)
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, account.id()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
    }

    private UUID venueHosted(List<UUID> resources) {
        NewTournament request = new NewTournament(UUID.randomUUID(), venue.id(),
                new NewTournament.Host.Venue(account.id()), Activity.PLAYSTATION, PlayMode.SINGLE, 8, startAt, 120,
                TournamentVisibility.PUBLIC, null, Money.of("50.00"), Money.of("1000.00"), sessionId, day, resources);
        runner.run(TestCommands.under(ps.e7gz.common.command.CommandScope.shared(request.lockSet()),
                c -> tournaments.create(c, request)));
        return request.id();
    }

    private static String status(UUID tournament) {
        return OWNER.sql("SELECT status || '/' || COALESCE(terminal_reason, '-') FROM tournament.tournament"
                + " WHERE id = :id").param("id", tournament).query(String.class).single();
    }

    private static long activeHolds(UUID tournament) {
        return OWNER.sql("SELECT count(*) FROM inventory.resource_reservation WHERE tournament_id = :id"
                + " AND active_flag").param("id", tournament).query(Long.class).single();
    }
}
