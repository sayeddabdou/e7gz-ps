package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.venue.api.HoursChangeBookings;

/**
 * E7-25 (S1-P3) against the real database: the opening-hours API (FR-VEN-17, FR-VEN-23…FR-VEN-27), session
 * generation over the horizon (ADR-009), {@code ex_session_no_overlap}, and referenced sessions staying as they are
 * ({@code venue.guard_operating_session}). Each test has its own venue. The DST cases are {@link SessionGeneratorTest}.
 */
@AutoConfigureMockMvc
class OpeningHoursHttpTest extends IntegrationTest {

    private static final String HOURS = "/api/v1/venue/opening-hours";
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    OpeningHoursService service;

    @Autowired
    OperatingSessionProperties properties;

    @Autowired
    CommandRunner runner;

    @Autowired
    ReservationLedger ledger;

    @Autowired
    ActiveCommitments commitments;

    @Autowired
    HoursChangeBookings bookings;

    @Autowired
    DataSource appDataSource;

    private UUID venue;
    private UUID resource;
    private String token;
    private LocalDate today;

    /** ReservationLedger.create publishes ReservationHeld, which carries the request's correlation ID (E7-46). */
    private CorrelationId.Scope correlation;

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @BeforeEach
    void anActiveVenueWithOneRoom() {
        correlation = CorrelationId.open(CorrelationId.generate());
        UUID area = UUID.randomUUID();
        OWNER.sql("INSERT INTO geo.area (id, name, is_active) VALUES (:id, :name, true)")
                .param("id", area).param("name", "E7-25 area " + area).update();
        venue = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, 'E7-25 venue', 'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ACTIVE',
                                'Africa/Cairo', '+20223456789')""")
                .param("id", venue).param("area", area).update();
        OWNER.sql("INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (:id, :venue, 'PLAYSTATION')")
                .param("id", UUID.randomUUID()).param("venue", venue).update();
        UUID account = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (:id, :venue, :login, 'x', 'ENABLED', 'en')""")
                .param("id", account).param("venue", venue).param("login", "e7-25-" + account).update();
        resource = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state,
                                                       operational_restriction)
                        VALUES (:id, :venue, 'PLAYSTATION', 'Room 1', 'PS5', 'ACTIVE', 'NONE')""")
                .param("id", resource).param("venue", venue).update();
        token = accessTokens.issue(AuthSubjectType.VENUE, account);
        today = LocalDate.now(CAIRO);
    }

    // ------------------------------------------------------------------------------------------------- the API

    @Test
    void beforeTheFirstSaveEveryDayIsClosed() throws Exception {
        call(get(HOURS), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("Africa/Cairo"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.days.length()").value(7))
                .andExpect(jsonPath("$.data.days[0].weekday").value(0))
                .andExpect(jsonPath("$.data.days[*].mode").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("CLOSED"))));
        assertThat(sessionDays()).isEmpty();
    }

    @Test
    void aSaveStoresTheHoursAndGeneratesEveryOpenDayOfTheHorizon() throws Exception {
        Map<String, Object> hours = proposal("12:00:00", "23:00:00", false, List.of(day(1, "CLOSED", null, null)));

        JsonNode preview = preview(hours).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected.length()").value(0))
                .andExpect(jsonPath("$.data.ownerPasswordRequired").value(false))
                .andReturn().getResponse().getContentAsString().transform(this::data);
        commit(hours, preview.path("previewToken").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.defaultOpen").value("12:00:00"))
                .andExpect(jsonPath("$.data.days[1].mode").value("CLOSED"))
                .andExpect(jsonPath("$.data.days[2].mode").value("DEFAULT"));

        List<LocalDate> expected = new ArrayList<>();
        for (LocalDate d = today.plusDays(1); d.isBefore(today.plusDays(properties.horizonDays())); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.TUESDAY) {
                expected.add(d);
            }
        }
        assertThat(sessionDays().stream().filter(d -> d.isAfter(today)).toList()).isEqualTo(expected);
        LocalDate day = expected.getFirst();
        Session session = session(day);
        assertThat(session.localStart()).isEqualTo(day.atTime(12, 0));
        assertThat(session.start()).isEqualTo(day.atTime(12, 0).atZone(CAIRO).toInstant());
        assertThat(session.end()).isEqualTo(day.atTime(23, 0).atZone(CAIRO).toInstant());
        assertThat(session.version()).isEqualTo(1);
        call(get(HOURS), null).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.defaultClose").value("23:00:00"));

        // A second change regenerates every session still to come, overnight this time.
        save(proposal("14:00:00", "02:00:00", false, List.of()));
        Session changed = session(day);
        assertThat(changed.localStart()).isEqualTo(day.atTime(14, 0));
        assertThat(changed.localEnd()).isEqualTo(day.plusDays(1).atTime(2, 0));
        assertThat(changed.version()).isEqualTo(2);
        assertThat(sessionDays()).contains(nextTuesday());
    }

    @Test
    void invalidHoursAreValidationFailedOnTheFieldAtFault() throws Exception {
        preview(proposal("02:00:00", "10:00:00", false, List.of(day(0, "CUSTOM", "20:00:00", "03:00:00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("days"));
        preview(proposal("10:00:00", "22:00:00", true, List.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("defaultClose"));
        call(get(HOURS), null).andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void aTokenFromAnotherPreviewIsRefusedAndNothingChanges() throws Exception {
        Map<String, Object> shown = proposal("12:00:00", "23:00:00", false, List.of());
        String token = previewToken(shown);

        commit(proposal("10:00:00", "23:00:00", false, List.of()), token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AFFECTED_SET_CHANGED_REFRESH_PREVIEW"));
        commit(shown, "v1.not-a-preview").andExpect(status().isConflict());
        call(get(HOURS), null).andExpect(jsonPath("$.data.version").value(0));
        assertThat(sessionDays()).isEmpty();
    }

    // ------------------------------------------------------------------------------------ the database rules

    @Test
    void anOverlappingSessionInsertFails() throws Exception {
        save(proposal("12:00:00", "23:00:00", false, List.of()));
        LocalDate day = today.plusDays(3);
        UUID template = OWNER.sql("SELECT id FROM venue.venue_opening_template WHERE venue_id = :venue")
                .param("venue", venue).query(UUID.class).single();

        // A session of a free operating day reaching into the next day's 12:00 session: ex_session_no_overlap.
        assertThatThrownBy(() -> insertSession(template, today.plusDays(200), day.atTime(11, 0), day.atTime(13, 0)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ex_session_no_overlap");
        // A second session for the same operating day: uq_operating_session_day.
        assertThatThrownBy(() -> insertSession(template, day, day.atTime(0, 0), day.atTime(1, 0)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uq_operating_session_day");
        // Touching is fine (FR-VEN-18).
        insertSession(template, today.plusDays(200), day.atTime(11, 0), day.atTime(12, 0));
    }

    @Test
    void aReferencedSessionIsNeverChangedAndIsSupersededBesideItsDaysCurrentSession() throws Exception {
        save(proposal("12:00:00", "23:00:00", false, List.of()));
        LocalDate day = today.plusDays(10);
        Session referenced = session(day);
        manualBooking(referenced, day.atTime(13, 0), day.atTime(14, 0));

        JdbcClient app = JdbcClient.create(appDataSource);
        assertThatThrownBy(() -> app.sql("DELETE FROM venue.operating_session WHERE id = :id")
                .param("id", referenced.id()).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> app.sql("UPDATE venue.operating_session SET local_end = local_end - interval '1 hour'"
                + " WHERE id = :id").param("id", referenced.id()).update()).isInstanceOf(DataAccessException.class);

        // New hours that still cover the Booking commit. The referenced session keeps its row and bounds (FR-VEN-22),
        // superseded (v1.1.37, E7-113); its day gets the new hours' current session beside it.
        save(proposal("10:00:00", "23:00:00", false, List.of()));
        assertThat(sessionById(referenced.id())).isEqualTo(referenced);
        assertThat(superseded(referenced.id())).isTrue();
        assertThat(session(day).localStart()).isEqualTo(day.atTime(10, 0));
        assertThat(session(day.plusDays(1)).localStart()).isEqualTo(day.plusDays(1).atTime(10, 0));
    }

    @Test
    void aBookingTheNewHoursNoLongerCoverIsPreviewedAndTheCommitCancelsIt() throws Exception {
        save(proposal("12:00:00", "23:00:00", false, List.of()));
        LocalDate day = today.plusDays(10);
        Session session = session(day);
        UUID booking = manualBooking(session, day.atTime(13, 0), day.atTime(14, 0));
        run(LockSet.builder().venues(venue).resources(resource).build(), ctx -> ledger.create(ctx,
                new NewReservation(resource, ReservationOwner.booking(booking), at(day.atTime(13, 0)),
                        at(day.atTime(14, 0)))));

        Map<String, Object> later = proposal("15:00:00", "23:00:00", false, List.of());
        JsonNode preview = preview(later).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affected.length()").value(1))
                .andExpect(jsonPath("$.data.affected[0].bookingId").value(booking.toString()))
                .andExpect(jsonPath("$.data.affected[0].resourceId").value(resource.toString()))
                .andExpect(jsonPath("$.data.affected[0].protectedCancellation").value(false))   // Manual (FR-VEN-25)
                .andExpect(jsonPath("$.data.ownerPasswordRequired").value(false))
                .andReturn().getResponse().getContentAsString().transform(this::data);
        assertThat(Instant.parse(preview.path("affected").get(0).path("startAt").asText()))
                .isEqualTo(at(day.atTime(13, 0)));

        // E7-113: the commit saves the hours and cancels it (a CONFIRMED Manual Booking needs no Owner Password).
        commit(later, preview.path("previewToken").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        assertThat(OWNER.sql("SELECT status || ' ' || terminal_reason FROM booking.booking WHERE id = :id")
                .param("id", booking).query(String.class).single()).isEqualTo("CANCELLED VENUE_OPENING_HOURS_CHANGED");
        assertThat(sessionById(session.id())).as("the session the booking referenced keeps its row").isEqualTo(session);
        assertThat(superseded(session.id())).isTrue();
        assertThat(session(day).localStart()).as("the day's current session").isEqualTo(day.atTime(15, 0));
        assertThat(session(day.plusDays(1)).localStart()).isEqualTo(day.plusDays(1).atTime(15, 0));

        // Hours that still cover it affect nothing.
        preview(proposal("11:00:00", "23:00:00", false, List.of()))
                .andExpect(jsonPath("$.data.affected.length()").value(0));
    }

    @Test
    void aTournamentHoldTheNewHoursNoLongerCoverBlocksTheSave() throws Exception {
        save(proposal("12:00:00", "23:00:00", false, List.of()));
        LocalDate day = today.plusDays(10);
        Session session = session(day);
        OpeningHours later = OpeningHours.validated(java.time.LocalTime.of(20, 0), java.time.LocalTime.of(23, 0),
                false, List.of());
        OpeningHoursCommands.Readers readers = new OpeningHoursCommands.Readers(commitments, bookings,
                properties.horizonDays());
        UUID tournament = UUID.randomUUID();

        // A Tournament is checked as a whole at commit, so it is written and previewed in one command, rolled back.
        assertThatThrownBy(() -> run(LockSet.builder().venues(venue).resources(resource).newTournaments(tournament)
                .build(), ctx -> {
            ctx.jdbc().sql("""
                    INSERT INTO tournament.tournament (id, venue_id, host_type, activity, play_mode, status, capacity,
                                                       created_at, start_at, end_at, nominal_minutes, checkpoint_at,
                                                       visibility, venue_cost_total, creation_fee_snapshot, entry_fee,
                                                       prize, business_date, operating_session_id)
                    VALUES (:id, :venue, 'VENUE', 'PLAYSTATION', 'SINGLE', 'WAITING_FOR_PLAYERS', 8,
                            :start, :start, :end, 60, :start, 'PUBLIC', 0, 0, 0, 0, :day, :session)
                    """)
                    .param("id", tournament).param("venue", venue).param("session", session.id())
                    .param("day", day)
                    .param("start", OffsetDateTime.ofInstant(at(day.atTime(18, 0)), CAIRO))
                    .param("end", OffsetDateTime.ofInstant(at(day.atTime(19, 0)), CAIRO))
                    .update();
            ledger.create(ctx, new NewReservation(resource, ReservationOwner.tournamentHold(tournament),
                    at(day.atTime(18, 0)), at(day.atTime(19, 0))));
            return new OpeningHoursCommands.Preview(venue, later, readers).execute(ctx);
        })).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE));
    }

    @Test
    void theNightlyRunAddsTheNewDayAndLeavesTheRestAlone() throws Exception {
        save(proposal("12:00:00", "23:00:00", false, List.of()));
        LocalDate last = today.plusDays(properties.horizonDays() - 1);
        OWNER.sql("DELETE FROM venue.operating_session WHERE venue_id = :venue AND operating_day = :day")
                .param("venue", venue).param("day", last).update();
        Session kept = session(today.plusDays(5));

        service.extendAll();

        assertThat(sessionDays()).contains(last);
        assertThat(session(today.plusDays(5))).isEqualTo(kept);
    }

    // ------------------------------------------------------------------------------------------------- helpers

    record Session(UUID id, LocalDateTime localStart, LocalDateTime localEnd, Instant start, Instant end,
                   long version) {
    }

    record TestCommand<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private <R> R run(LockSet locks, Function<CommandContext, R> body) {
        return runner.run(new TestCommand<>(CommandScope.shared(locks), body));
    }

    private static Instant at(LocalDateTime local) {
        return local.atZone(CAIRO).toInstant();
    }

    private LocalDate nextTuesday() {
        LocalDate d = today.plusDays(1);
        while (d.getDayOfWeek() != DayOfWeek.TUESDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    private static Map<String, Object> proposal(String open, String close, boolean fullDay,
                                                List<Map<String, Object>> days) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("defaultOpen", open);
        proposal.put("defaultClose", close);
        proposal.put("defaultFullDay", fullDay);
        proposal.put("days", days);
        return proposal;
    }

    private static Map<String, Object> day(int weekday, String mode, String open, String close) {
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("weekday", weekday);
        day.put("mode", mode);
        day.put("customOpen", open);
        day.put("customClose", close);
        return day;
    }

    private ResultActions preview(Map<String, Object> proposal) throws Exception {
        return call(post(HOURS + "/preview"), Map.of("proposal", proposal));
    }

    private ResultActions commit(Map<String, Object> proposal, String previewToken) throws Exception {
        return call(post(HOURS + "/commit").header("Idempotency-Key", UUID.randomUUID().toString()),
                Map.of("proposal", proposal, "previewToken", previewToken));
    }

    private String previewToken(Map<String, Object> proposal) throws Exception {
        return preview(proposal).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
                .transform(this::data).path("previewToken").asText();
    }

    private void save(Map<String, Object> proposal) throws Exception {
        commit(proposal, previewToken(proposal)).andExpect(status().isOk());
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode data(String response) {
        try {
            return json.readTree(response).path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean superseded(UUID sessionId) {
        return OWNER.sql("SELECT superseded_at IS NOT NULL FROM venue.operating_session WHERE id = :id")
                .param("id", sessionId).query(Boolean.class).single();
    }

    private List<LocalDate> sessionDays() {
        return OWNER.sql("SELECT operating_day FROM venue.operating_session WHERE venue_id = :venue"
                        + " AND superseded_at IS NULL ORDER BY 1")
                .param("venue", venue).query(LocalDate.class).list();
    }

    /** The day's current session (v1.1.37: a superseded one may stand beside it). */
    private Session session(LocalDate day) {
        return OWNER.sql("""
                        SELECT id, local_start, local_end, lower(session_period) AS s, upper(session_period) AS e,
                               generated_from_version
                          FROM venue.operating_session
                         WHERE venue_id = :venue AND operating_day = :day AND superseded_at IS NULL""")
                .param("venue", venue).param("day", day)
                .query((rs, n) -> new Session(rs.getObject("id", UUID.class),
                        rs.getObject("local_start", LocalDateTime.class),
                        rs.getObject("local_end", LocalDateTime.class),
                        rs.getObject("s", OffsetDateTime.class).toInstant(),
                        rs.getObject("e", OffsetDateTime.class).toInstant(), rs.getLong("generated_from_version")))
                .single();
    }

    /** One session row by id, superseded or not, and whether it is superseded. */
    private Session sessionById(UUID id) {
        return OWNER.sql("""
                        SELECT id, local_start, local_end, lower(session_period) AS s, upper(session_period) AS e,
                               generated_from_version
                          FROM venue.operating_session WHERE id = :id""")
                .param("id", id)
                .query((rs, n) -> new Session(rs.getObject("id", UUID.class),
                        rs.getObject("local_start", LocalDateTime.class),
                        rs.getObject("local_end", LocalDateTime.class),
                        rs.getObject("s", OffsetDateTime.class).toInstant(),
                        rs.getObject("e", OffsetDateTime.class).toInstant(), rs.getLong("generated_from_version")))
                .single();
    }

    private void insertSession(UUID template, LocalDate operatingDay, LocalDateTime start, LocalDateTime end) {
        OWNER.sql("""
                        INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day,
                                                             local_start, local_end, session_period, timezone,
                                                             generated_from_version, generated_at)
                        VALUES (:id, :venue, :template, :day, :start, :end,
                                tstzrange(:start AT TIME ZONE 'Africa/Cairo', :end AT TIME ZONE 'Africa/Cairo', '[)'),
                                'Africa/Cairo', 1, now())""")
                .param("id", UUID.randomUUID()).param("venue", venue).param("template", template)
                .param("day", operatingDay).param("start", start).param("end", end).update();
    }

    /** A future CONFIRMED Manual Booking inside the session, with its session snapshot (FR-VEN-22). */
    private UUID manualBooking(Session session, LocalDateTime start, LocalDateTime end) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date, operating_day,
                                                     operating_session_id, session_local_start, session_local_end,
                                                     session_timezone, created_at)
                        VALUES (:id, :venue, :resource, NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
                                'Room 1', :start, :end, 60, 100.00, 100.00, 0.00, 100.00, :day, :day, :session,
                                :localStart, :localEnd, 'Africa/Cairo', now())""")
                .param("id", id).param("venue", venue).param("resource", resource)
                .param("start", OffsetDateTime.ofInstant(at(start), CAIRO))
                .param("end", OffsetDateTime.ofInstant(at(end), CAIRO))
                .param("day", session.localStart().toLocalDate()).param("session", session.id())
                .param("localStart", session.localStart()).param("localEnd", session.localEnd()).update();
        return id;
    }
}
