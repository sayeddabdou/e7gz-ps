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
 * E7-84 (S5-E4) — the Tournament part of the Owner Review entry cascade (arch 06 section 2.7, FR-OPS-28, FR-TRN-31): a
 * pre-start Tournament using a room that enters OWNER_REVIEW_REQUIRED at the end of its Emergency is CANCELLED under
 * RESOURCE_OWNER_REVIEW_REQUIRED by the system, its holds released. The ACTIVE branch (it loses the room and goes on)
 * is not tested, as in {@link EmergencyTournamentCascadeTest}: nothing reaches ACTIVE before the Tournament start of
 * Sprint 6.
 */
@AutoConfigureMockMvc
class OwnerReviewTournamentCascadeTest extends IntegrationTest {

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
    void anOwnerReviewEnteredAtTheEndOfAnEmergencyCancelsAPreStartTournamentUsingTheRoom() throws Exception {
        UUID tournament = venueHosted(rooms);
        String emergency = declare("/resource-outage", "{\"resourceId\":\"" + rooms.getFirst()
                + "\",\"reason\":\"Overheating\",\"expectedReturnAt\":\""
                + OWNER.sql("SELECT clock_timestamp() + interval '30 minutes'").query(OffsetDateTime.class).single()
                        .toInstant() + "\"}");
        assertThat(status(tournament)).as("outside the Emergency's window").isEqualTo("WAITING_FOR_PLAYERS/-");
        overThreshold(rooms.getFirst());

        mvc.perform(post("/api/v1/venue/emergencies/" + emergency + "/end")
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, account.id()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        assertThat(status(tournament)).isEqualTo("CANCELLED/RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(activeHolds(tournament)).isZero();
        assertThat(OWNER.sql("SELECT count(*) FROM operations.operational_interruption_record WHERE subject_id = :id"
                + " AND subject_type = 'TOURNAMENT' AND cause = 'RESOURCE_OWNER_REVIEW_REQUIRED'")
                .param("id", tournament).query(Long.class).single()).isOne();
        assertThat(OWNER.sql("SELECT count(*) FROM tournament.tournament_history WHERE tournament_id = :id"
                + " AND to_status = 'CANCELLED' AND actor_type = 'SYSTEM'").param("id", tournament)
                .query(Long.class).single()).as("a system consequence, no Owner Password (FR-OPS-29)").isOne();
    }

    /** @return the declared Emergency's id */
    private String declare(String path, String body) throws Exception {
        String response = mvc.perform(post("/api/v1/venue/emergencies" + path)
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, account.id()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("id").asText();
    }

    private UUID venueHosted(List<UUID> resources) {
        NewTournament request = new NewTournament(UUID.randomUUID(), venue.id(),
                new NewTournament.Host.Venue(account.id()), Activity.PLAYSTATION, PlayMode.SINGLE, 8, startAt, 120,
                TournamentVisibility.PUBLIC, null, Money.of("50.00"), Money.of("1000.00"), sessionId, day, resources);
        runner.run(TestCommands.under(ps.e7gz.common.command.CommandScope.shared(request.lockSet()),
                c -> tournaments.create(c, request)));
        return request.id();
    }

    /** 26 hours of Maintenance that ended four hours ago, behind the guards: over the rolling threshold (FR-OPS-26). */
    private static void overThreshold(UUID room) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            jdbc.sql("""
                            INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
                            VALUES (gen_random_uuid(), :room, tstzrange(clock_timestamp() - interval '30 hours',
                                    clock_timestamp() - interval '4 hours', '[)'), 'Repeated faults', true)""")
                    .param("room", room).update();
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
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
