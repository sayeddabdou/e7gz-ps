package ps.e7gz.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-48 / v1.1.34 (PCR-007; Product Owner decision 10 Oct 2026, E7-48): a MANUAL booking may go ACTIVE -> CANCELLED
 * with VENUE_CANCELLED_MANUAL_BOOKING, and only with exactly one matching VENUE history transition by an account of
 * the booking's own venue, checked at commit by {@code tr_booking_manual_active_cancel_provenance}.
 *
 * <p>Every trigger is on. The fixtures (an ACTIVE manual walk-in with its BOOKING reservation) are written by the owner,
 * as the upgrade seed does - there is no manual-booking command before Phase B. Each cancellation is written as the
 * application role inside a real command ({@link CommandRunner}), so the deferred check runs at that command's commit.
 * The check verifies the RECORDED actor provenance, not an independently authenticated identity (see the migration).
 */
class ManualActiveCancelProvenanceTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private static final String REASON = "VENUE_CANCELLED_MANUAL_BOOKING";
    private static final String REFUSAL =
            "ACTIVE Manual Booking cancellation requires exactly one matching Venue Booking History transition";

    private static final UUID AREA = UUID.fromString("e7480a20-0000-4000-8000-000000000001");
    private static final UUID VENUE = UUID.fromString("e7480a20-0000-4000-8000-000000000011");
    private static final UUID OTHER_VENUE = UUID.fromString("e7480a20-0000-4000-8000-000000000012");
    private static final UUID VENUE_ACCOUNT = UUID.fromString("e7480a20-0000-4000-8000-000000000021");
    private static final UUID OTHER_VENUE_ACCOUNT = UUID.fromString("e7480a20-0000-4000-8000-000000000022");
    private static final UUID ADMIN = UUID.fromString("e7480a20-0000-4000-8000-000000000031");

    private static boolean loaded;

    @Autowired
    CommandRunner runner;

    /** One ACTIVE/CONFIRMED -> CANCELLED history row the test command writes, at the command instant + {@code shift}. */
    record History(String from, String reason, String actorType, UUID actorId, Duration shift) {

        static History venue(UUID account) {
            return new History("ACTIVE", REASON, "VENUE", account, Duration.ZERO);
        }
    }

    /** An ACTIVE MANUAL walk-in and its active BOOKING reservation. */
    record Walkin(UUID booking, UUID resource, UUID reservation) {
    }

    record Cancel(LockSet locks, Function<CommandContext, Instant> body)
            implements Command<Instant> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public Instant execute(CommandContext context) {
            return body.apply(context);
        }
    }

    @BeforeEach
    void fixtures() {
        load();
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        OWNER.sql("INSERT INTO geo.area (id, name) VALUES (:id, 'E7-48 PCR-007 provenance area')")
                .param("id", AREA).update();
        for (UUID venue : List.of(VENUE, OTHER_VENUE)) {
            OWNER.sql("""
                    INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                    VALUES (:id, :area, :name, 'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ACTIVE',
                            'Africa/Cairo', '+20223456789')""")
                    .param("id", venue).param("area", AREA).param("name", "E7-48 provenance " + venue).update();
            OWNER.sql("INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (:id, :venue, 'BILLIARDS')")
                    .param("id", UUID.randomUUID()).param("venue", venue).update();
        }
        account(VENUE_ACCOUNT, VENUE, "e7-48-provenance-a");
        account(OTHER_VENUE_ACCOUNT, OTHER_VENUE, "e7-48-provenance-b");
        OWNER.sql("""
                INSERT INTO iam.admin_account (id, email, password_hash, full_name)
                VALUES (:id, 'e7-48-provenance-admin@example.com', 'x', 'E7-48 provenance admin')""")
                .param("id", ADMIN).update();
        loaded = true;
    }

    private static void account(UUID id, UUID venue, String login) {
        OWNER.sql("""
                INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                               language_preference)
                VALUES (:id, :venue, :login, 'x', 'ENABLED', 'en')""")
                .param("id", id).param("venue", venue).param("login", login).update();
    }

    /** A fresh walk-in on its own billiards table: started now, 60 minutes, no Operating Session (out of hours). */
    private static Walkin walkin() {
        UUID resource = UUID.randomUUID();
        UUID booking = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        OWNER.sql("""
                INSERT INTO resource.resource (id, venue_id, activity, name, base_state, operational_restriction)
                VALUES (:id, :venue, 'BILLIARDS', :name, 'ACTIVE', 'NONE')""")
                .param("id", resource).param("venue", VENUE).param("name", "Table " + resource).update();
        OWNER.sql("""
                INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                             resource_name_snapshot, start_at, end_at, nominal_booked_minutes,
                                             hourly_price_snapshot, venue_price_total, booking_fee_snapshot,
                                             total_amount, business_date, session_timezone, manual_customer_name,
                                             created_at)
                SELECT :id, :venue, :resource, NULL, 'MANUAL', 'ACTIVE', 'BILLIARDS', :name, t.now,
                       t.now + interval '60 minutes', 60, 100.00, 100.00, 0.00, 100.00,
                       (t.now AT TIME ZONE 'Africa/Cairo')::date, 'Africa/Cairo', 'Walk-in', t.now
                  FROM (SELECT now() AS now) t""")
                .param("id", booking).param("venue", VENUE).param("resource", resource)
                .param("name", "Table " + resource).update();
        OWNER.sql("""
                INSERT INTO inventory.resource_reservation (id, resource_id, reservation_type, booking_id,
                                                            planned_start, planned_end, actual_reservation_start)
                SELECT :id, b.resource_id, 'BOOKING', b.id, b.start_at, b.end_at, b.start_at
                  FROM booking.booking b WHERE b.id = :booking""")
                .param("id", reservation).param("booking", booking).update();
        OWNER.sql("""
                INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind, from_status,
                                                     to_status, actor_type, actor_id, correlation_id)
                SELECT gen_random_uuid(), b.id, 1, b.created_at, 'STATUS_TRANSITION', NULL, 'ACTIVE', 'VENUE',
                       :account, gen_random_uuid()
                  FROM booking.booking b WHERE b.id = :booking""")
                .param("account", VENUE_ACCOUNT).param("booking", booking).update();
        return new Walkin(booking, resource, reservation);
    }

    /**
     * As the application role in one command: ACTIVE -> CANCELLED / VENUE_CANCELLED_MANUAL_BOOKING at the command
     * instant, the reservation released at that instant, then the given history rows. Returns the command instant.
     */
    private Instant cancel(Walkin walkin, List<History> history) {
        return runner.run(new Cancel(LockSet.builder().venues(VENUE).resources(walkin.resource()).build(), ctx -> {
            Instant now = ctx.commandNow();
            int changed = ctx.jdbc().sql("""
                    UPDATE booking.booking
                       SET status = 'CANCELLED', terminal_reason = :reason, terminal_at = :at,
                           fee_treatment = booking.expected_fee_treatment(:reason), version = version + 1
                     WHERE id = :id AND status = 'ACTIVE'""")
                    .param("reason", REASON).param("at", utc(now)).param("id", walkin.booking()).update();
            assertThat(changed).isEqualTo(1);
            ctx.jdbc().sql("""
                    UPDATE inventory.resource_reservation
                       SET active_flag = false, actual_reservation_release_at = :at, release_reason = :reason
                     WHERE id = :id AND active_flag = true""")
                    .param("at", utc(now)).param("reason", REASON).param("id", walkin.reservation()).update();
            int sequence = 2;
            for (History h : history) {
                ctx.jdbc().sql("""
                        INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind,
                                                             from_status, to_status, terminal_reason, actor_type,
                                                             actor_id, correlation_id)
                        VALUES (:id, :booking, :sequence, :at, 'STATUS_TRANSITION', :from, 'CANCELLED', :reason,
                                :actorType, :actorId, :correlation)""")
                        .param("id", UUID.randomUUID()).param("booking", walkin.booking())
                        .param("sequence", sequence++).param("at", utc(now.plus(h.shift())))
                        .param("from", h.from()).param("reason", h.reason())
                        .param("actorType", h.actorType()).param("actorId", h.actorId())
                        .param("correlation", UUID.randomUUID()).update();
            }
            return now;
        }));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String status(UUID booking) {
        return OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", booking)
                .query(String.class).single();
    }

    private static boolean reservationActive(UUID reservation) {
        return OWNER.sql("SELECT active_flag FROM inventory.resource_reservation WHERE id = :id")
                .param("id", reservation).query(Boolean.class).single();
    }

    private static long historyRows(UUID booking) {
        return OWNER.sql("SELECT count(*) FROM booking.booking_history WHERE booking_id = :id")
                .param("id", booking).query(Long.class).single();
    }

    /** Refused at commit by the provenance check, and nothing of the command was kept. */
    private void assertRefused(List<History> history) {
        Walkin walkin = walkin();

        Throwable failure = catchThrowable(() -> cancel(walkin, history));

        assertThat(failure).as("the command is refused at commit").isNotNull();
        assertThat(DatabaseErrorCodes.serverError(failure)).hasValueSatisfying(error -> {
            assertThat(error.getSQLState()).isEqualTo(DatabaseErrorCodes.RAISE_EXCEPTION);
            assertThat(error.getMessage()).startsWith(REFUSAL);
            assertThat(error.getConstraint()).as("an internal invariant, no guard identifier").isNull();
        });
        assertThat(status(walkin.booking())).isEqualTo("ACTIVE");
        assertThat(reservationActive(walkin.reservation())).isTrue();
        assertThat(historyRows(walkin.booking())).isEqualTo(1L);
    }

    // ---- the one allowed shape -------------------------------------------------------------------------------------

    @Test
    void aVenueOfTheBookingCancelsItsActiveManualBookingAndTheRoomIsFreeFromThatInstant() {
        Walkin walkin = walkin();

        Instant at = cancel(walkin, List.of(History.venue(VENUE_ACCOUNT)));

        assertThat(status(walkin.booking())).isEqualTo("CANCELLED");
        var row = OWNER.sql("""
                SELECT b.terminal_reason, b.fee_treatment, b.terminal_at, r.active_flag, r.actual_reservation_release_at
                  FROM booking.booking b JOIN inventory.resource_reservation r ON r.booking_id = b.id
                 WHERE b.id = :id""").param("id", walkin.booking()).query().singleRow();
        assertThat(row.get("terminal_reason")).isEqualTo(REASON);
        assertThat(row.get("fee_treatment")).isEqualTo("NOT_APPLICABLE");
        assertThat(((Timestamp) row.get("terminal_at")).toInstant()).isEqualTo(at);
        assertThat(row.get("active_flag")).isEqualTo(false);
        assertThat(((Timestamp) row.get("actual_reservation_release_at")).toInstant()).isEqualTo(at);
        assertThat(historyRows(walkin.booking())).isEqualTo(2L);
    }

    // ---- other actors ----------------------------------------------------------------------------------------------

    @Test
    void anAdminActorIsRefused() {
        assertRefused(List.of(new History("ACTIVE", REASON, "ADMIN", ADMIN, Duration.ZERO)));
    }

    @Test
    void aSystemActorIsRefused() {
        assertRefused(List.of(new History("ACTIVE", REASON, "SYSTEM", null, Duration.ZERO)));
    }

    @Test
    void aNullActorIsRefused() {
        assertRefused(List.of(new History("ACTIVE", REASON, null, null, Duration.ZERO)));
    }

    @Test
    void anAccountOfAnotherVenueIsRefused() {
        assertRefused(List.of(History.venue(OTHER_VENUE_ACCOUNT)));
    }

    @Test
    void aVenueActorThatIsNoVenueAccountIsRefused() {
        assertRefused(List.of(History.venue(UUID.randomUUID())));
    }

    // ---- missing, duplicate and mismatched history -----------------------------------------------------------------

    @Test
    void noHistoryRowIsRefused() {
        assertRefused(List.of());
    }

    @Test
    void twoMatchingHistoryRowsAreRefused() {
        assertRefused(List.of(History.venue(VENUE_ACCOUNT), History.venue(VENUE_ACCOUNT)));
    }

    @Test
    void aMatchingRowNextToAnAdminRowIsRefused() {
        assertRefused(List.of(History.venue(VENUE_ACCOUNT),
                new History("ACTIVE", REASON, "ADMIN", ADMIN, Duration.ZERO)));
    }

    @Test
    void anotherReasonInTheHistoryIsRefused() {
        assertRefused(List.of(new History("ACTIVE", "ADMIN_CANCELLED", "VENUE", VENUE_ACCOUNT, Duration.ZERO)));
    }

    @Test
    void anotherInstantInTheHistoryIsRefused() {
        assertRefused(List.of(new History("ACTIVE", REASON, "VENUE", VENUE_ACCOUNT, Duration.ofMillis(1))));
    }

    @Test
    void anotherFromStatusInTheHistoryIsRefused() {
        assertRefused(List.of(new History("CONFIRMED", REASON, "VENUE", VENUE_ACCOUNT, Duration.ZERO)));
    }

    // ---- every other transition is unchanged -----------------------------------------------------------------------

    /**
     * The guard's whole decision table, V1's copy against the migrated one, on scratch rows (the guard function only,
     * as {@code test_behaviour_v127.sh} does): every from x to x reason (or none) x source, once with startAt/endAt in
     * the future and once in the past. The only answer that changes is ACTIVE -> CANCELLED /
     * VENUE_CANCELLED_MANUAL_BOOKING / MANUAL; ONLINE stays refused.
     */
    @Test
    void everyOtherTransitionGivesTheV1Answer() throws Exception {
        String v1 = resource("db/migration/V1__baseline.sql");
        String open = "CREATE OR REPLACE FUNCTION booking.guard_booking_transition()";
        int start = v1.indexOf(open);
        String v1Guard = v1.substring(start, v1.indexOf("$function$;\n", start) + "$function$;\n".length())
                .replace(open, "CREATE OR REPLACE FUNCTION pg_temp.v1_guard_booking_transition()");

        List<String> changed = new ArrayList<>();
        long combinations;
        try (Connection c = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(v1Guard);
                s.execute(resource("ps/e7gz/booking/guard-transition-matrix.sql"));
                try (var rs = s.executeQuery("""
                        SELECT phase || ' ' || from_s || '->' || to_s || ' ' || COALESCE(reason, '-') || ' ' || source
                               || ' v1=' || v1 || ' now=' || migrated
                          FROM pg_temp.guard_matrix WHERE v1 IS DISTINCT FROM migrated ORDER BY 1""")) {
                    while (rs.next()) {
                        changed.add(rs.getString(1));
                    }
                }
                try (var rs = s.executeQuery("SELECT count(*) FROM pg_temp.guard_matrix")) {
                    rs.next();
                    combinations = rs.getLong(1);
                }
            } finally {
                c.rollback();
            }
        }

        // 9 statuses x 9 x (22 reasons + none) x 2 sources x 2 phases
        assertThat(combinations).isEqualTo(9L * 9 * 23 * 2 * 2);
        assertThat(changed).containsExactly(
                "future ACTIVE->CANCELLED VENUE_CANCELLED_MANUAL_BOOKING MANUAL v1=false now=true",
                "past ACTIVE->CANCELLED VENUE_CANCELLED_MANUAL_BOOKING MANUAL v1=false now=true");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = ManualActiveCancelProvenanceTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
