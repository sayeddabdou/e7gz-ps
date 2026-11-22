package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.faultinjection.FaultInjection;
import ps.e7gz.faultinjection.FaultInjection.Faults;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.notification.api.VenueBrowserPush;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.venue.api.AccountDisableBookings;
import ps.e7gz.venue.api.AccountDisableTournaments;
import ps.e7gz.venue.api.AccountRestrictions;

/**
 * E7-79 (PO decision 22 Nov 2026, G3): fault injection at every step of E7-78's Venue Account disable (O10, arch 06
 * section 2.8) leaves no partial cascade. One venue holds a PENDING request, an AWAITING Late Confirmation and an ACTIVE
 * session; a disable that fails at any step - the bookings, Tournaments, restriction history, outbox, browser push or
 * audit - leaves the account ENABLED with no restriction interval, every booking as it was with its reservation or
 * hold, no candidate, alert, refund decision, audit row or event (so no notification).
 */
class VenueAccountDisableFaultInjectionTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    private BookingFlows flows;
    private UUID admin;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
        admin = AuditFixtures.admin();
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /**
     * One venue and customer: an ACTIVE session, a PENDING request and an AWAITING Late Confirmation, and the last
     * outbox event id once they exist - making them wrote events of their own (the Late Confirmation's expiry first).
     */
    private record World(BookingFlows.Booking active, BookingFlows.Booking pending, BookingFlows.Booking awaiting,
                         long eventsBefore) {

        UUID venueId() {
            return active.venueId();
        }

        UUID accountId() {
            return active.venueAccountId();
        }
    }

    private World world() {
        BookingFlows.Customer customer = flows.customer();
        BookingFlows.Booking active = customer.nextActive(Duration.ofMinutes(20));
        BookingFlows.Booking pending = customer.nextPending(Duration.ofHours(3));
        BookingFlows.Booking awaiting = customer.nextLateConfirmed(Duration.ofHours(6));
        return new World(active, pending, awaiting, count("SELECT COALESCE(max(id), 0) FROM technical.outbox_event"));
    }

    @Test
    void aFaultAtEveryStepOfTheDisableLeavesNothingBehind() {
        FaultInjection.atEveryStep(this::world, this::disable, counted -> {
            assertThat(statusOf(counted.pending())).isEqualTo("VENUE_RESPONSE_EXPIRED/-");
            assertThat(statusOf(counted.awaiting())).isEqualTo("CANCELLED/VENUE_ACCOUNT_DISABLED");
            assertThat(statusOf(counted.active())).isEqualTo("ACTIVE/-");
            assertThat(accountStatus(counted)).isEqualTo("DISABLED");
        }, 8, VenueAccountDisableFaultInjectionTest::nothingLeft);
    }

    /** The real disable command with every port it calls wrapped; the lock plan reads through the real ones. */
    private void disable(World world, Faults faults) {
        VenueAccountCommands.DisableDeps real = new VenueAccountCommands.DisableDeps(
                context.getBean(AccountDisableBookings.class), context.getBean(AccountDisableTournaments.class),
                context.getBean(AccountRestrictions.class), context.getBean(AuditWriter.class),
                context.getBean(Outbox.class), context.getBean(VenueBrowserPush.class));
        VenueAccountCommands.DisableDeps faulty = new VenueAccountCommands.DisableDeps(
                faults.wrap(AccountDisableBookings.class, real.bookings()),
                faults.wrap(AccountDisableTournaments.class, real.tournaments()),
                faults.wrap(AccountRestrictions.class, real.restrictions()),
                faults.wrap(AuditWriter.class, real.audit()),
                faults.wrap(Outbox.class, real.outbox()),
                faults.wrap(VenueBrowserPush.class, real.browserPush()));
        LockSet locks = runner.run(new VenueAccountCommands.PlanStatus(world.accountId(), world.venueId(),
                VenueAccountStatus.DISABLED, real));
        runner.run(new VenueAccountCommands.SetAccountStatus(admin, CorrelationId.require(), world.accountId(),
                world.venueId(), VenueAccountStatus.DISABLED, "Support ticket 7", locks, faulty));
    }

    private static void nothingLeft(World world, int step) {
        String venue = world.venueId().toString();
        assertThat(accountStatus(world)).as("step %d: still ENABLED", step).isEqualTo("ENABLED");
        assertThat(count("SELECT count(*) FROM iam.venue_account_restriction_interval WHERE venue_id = '" + venue
                + "'")).as("step %d: no restriction interval", step).isZero();
        assertThat(statusOf(world.pending())).as("step %d", step).isEqualTo("PENDING/-");
        assertThat(statusOf(world.awaiting())).as("step %d", step).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION/-");
        assertThat(statusOf(world.active())).as("step %d", step).isEqualTo("ACTIVE/-");
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE active_flag AND booking_id IN ('"
                + world.pending().bookingId() + "','" + world.awaiting().bookingId() + "','"
                + world.active().bookingId() + "')")).as("step %d: every reservation and hold still held", step)
                .isEqualTo(3);
        assertThat(count("SELECT count(*) FROM booking.booking_history WHERE event_type = "
                + "'VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE' AND booking_id = '" + world.pending().bookingId()
                + "'")).as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE type = 'VENUE_RESPONSE_EXPIRED_RESOLUTION'"
                + " AND subject_id = '" + world.pending().bookingId() + "'")).as("step %d: no T6", step).isZero();
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = '"
                + world.awaiting().bookingId() + "'")).as("step %d: no refund decision", step).isZero();
        assertThat(count("SELECT count(*) FROM attendance.attendance_protection_candidate WHERE booking_id = '"
                + world.active().bookingId() + "'")).as("step %d: no candidate", step).isZero();
        assertThat(count("SELECT count(*) FROM admin.admin_alert WHERE subject_id = '" + world.active().bookingId()
                + "'")).as("step %d: no alert", step).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'VENUE_ACCOUNT_DISABLED'"
                + " AND context_venue_id = '" + venue + "'")).as("step %d: no audit row", step).isZero();
        assertThat(count("SELECT count(*) FROM technical.outbox_event WHERE id > " + world.eventsBefore()
                + " AND payload->>'venueId' = '" + venue + "' AND event_type IN ('VenueAccountDisabled','VenueResponseExpired','BookingCancelled',"
                + "'ActiveSessionAttendanceRecordingAffected')")).as("step %d: no event, so no notification", step)
                .isZero();
    }

    private static String statusOf(BookingFlows.Booking booking) {
        return OWNER.sql("SELECT status || '/' || COALESCE(terminal_reason, '-') FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query(String.class).single();
    }

    private static String accountStatus(World world) {
        return OWNER.sql("SELECT account_status FROM iam.venue_account WHERE id = :id").param("id", world.accountId())
                .query(String.class).single();
    }

    private static long count(String sql) {
        return OWNER.sql(sql).query(Long.class).single();
    }
}
