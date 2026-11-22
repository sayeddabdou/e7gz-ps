package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.operations.internal.EmergencyHttpTest.count;
import static ps.e7gz.operations.internal.EmergencyHttpTest.dbNow;
import static ps.e7gz.operations.internal.EmergencyHttpTest.statusOf;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.EmergencyBookings;
import ps.e7gz.operations.api.UnresolvedEmergencies;
import ps.e7gz.operations.internal.EmergencyCommands.Deps;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.faultinjection.FaultInjection;
import ps.e7gz.faultinjection.FaultInjection.Faults;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.tournament.api.TournamentCascades;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * E7-82 "done when": fault injection at every step of the Emergency cascade leaves no partial cascade (FR-OPS-09, arch
 * 06 section 2.5: all-or-nothing, persisted atomically before notifications dispatch). One successful declaration
 * counts the steps - every call the command makes to the bookings, Tournaments, scheduler, audit and outbox - then the
 * same declaration on a fresh world fails at each step in turn, and nothing of it may remain: no Emergency, no selling
 * block, no cancellation, no interruption, no paused timer, no refund decision, no Trust outcome, no audit row, no job
 * and no event (so no notification).
 */
class EmergencyCascadeFaultInjectionTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueEmergencies emergencies;

    private BookingFlows flows;
    private AttendanceFlows attendance;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void flows() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    /** One venue: a PENDING request in the window, one outside it (to pause), and a checked-in ACTIVE booking. */
    private record World(BookingFlows.Booking inside, BookingFlows.Booking outside, BookingFlows.Booking active) {

        UUID venueId() {
            return inside.venueId();
        }
    }

    private World world() {
        List<BookingFlows.Booking> three = flows.twoPendingAndAnActiveAtOneVenue(Duration.ofMinutes(5));
        attendance.checkIn(three.get(2).venueAccountId(), three.get(2).bookingId());
        return new World(three.get(0), three.get(1), three.get(2));
    }

    @Test
    void aFaultAtEveryStepOfTheCascadeLeavesNothingBehind() {
        // E7-79: the shared harness counts the steps, then fails at each one on a fresh world
        FaultInjection.atEveryStep(this::world, this::declare, counted -> {
            assertThat(statusOf(counted.inside().bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
            assertThat(statusOf(counted.active().bookingId())).isEqualTo("CANCELLED/VENUE_EMERGENCY_CLOSURE");
        }, 8, EmergencyCascadeFaultInjectionTest::nothingLeft);
    }

    private void declare(World world, Faults faults) {
        Deps real = emergencies.deps();
        EmergencyBookings bookings = faults.wrap(EmergencyBookings.class, real.bookings());
        Outbox outbox = faults.wrap(Outbox.class, real.outbox());
        ScheduledJobs jobs = faults.wrap(ScheduledJobs.class, real.jobs());
        // E7-83: the pauses go through the same faulty ports
        TimerPauses timers = new TimerPauses(bookings, context.getBean(UnresolvedEmergencies.class),
                context.getBean(OperatingSessions.class), context.getBean(VenueEligibility.class),
                context.getBean(ResourceStates.class), jobs, outbox);
        Deps faulty = new Deps(bookings,
                faults.wrap(TournamentCascades.class, real.tournaments()), real.resources(), real.catalog(),
                real.sessions(), real.commitments(), real.baseStates(),
                faults.wrap(AuditWriter.class, real.audit()),
                outbox, jobs, real.gate(), real.closures(), real.json(), timers, real.alerts());
        EmergencyCommands.Subject subject = new EmergencyCommands.Subject(world.venueId(), EmergencyScope.VENUE, null);
        Instant end = world.inside().startAt().plus(Duration.ofMinutes(60));
        LockSet locks = runner.run(new EmergencyCommands.Plan(world.venueId(), subject, null, end, real));
        runner.run(new EmergencyCommands.DeclareVenue(new EmergencyCommands.Declaration(subject,
                world.inside().venueAccountId(), CorrelationId.require(), "Power cut", end, locks, faulty)));
    }

    private static void nothingLeft(World world, int step) {
        String venue = world.venueId().toString();
        assertThat(statusOf(world.inside().bookingId())).as("step %d", step).isEqualTo("PENDING/-");
        assertThat(statusOf(world.active().bookingId())).as("step %d", step).isEqualTo("ACTIVE/-");
        assertThat(count("SELECT count(*) FROM booking.booking WHERE id = '" + world.outside().bookingId()
                + "' AND response_pause_started_at IS NULL")).as("step %d: not paused", step).isOne();
        assertThat(count("SELECT count(*) FROM operations.emergency WHERE venue_id = '" + venue + "'"))
                .as("step %d: no Emergency", step).isZero();
        assertThat(count("SELECT count(*) FROM venue.venue_selling_block WHERE venue_id = '" + venue + "'"))
                .as("step %d: no block", step).isZero();
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation rr JOIN booking.booking b"
                + " ON b.id = rr.booking_id WHERE b.venue_id = '" + venue + "' AND rr.active_flag"))
                .as("step %d: every reservation still held", step).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM operations.booking_timer_pause_history h JOIN booking.booking b"
                + " ON b.id = h.booking_id WHERE b.venue_id = '" + venue + "'")).as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund f JOIN booking.booking b"
                + " ON b.id = f.booking_id WHERE b.venue_id = '" + venue + "'")).as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM attendance.attendance_record a WHERE a.booking_id = '"
                + world.active().bookingId() + "' AND a.result IS NOT NULL")).as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM trust.trust_event WHERE booking_id = '" + world.active().bookingId()
                + "'")).as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM operations.operational_interruption_record r JOIN booking.booking b"
                + " ON b.id = r.subject_id WHERE b.venue_id = '" + venue + "'")).as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'EMERGENCY_DECLARED'"
                + " AND context_venue_id = '" + venue + "'")).as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM technical.scheduled_job j JOIN operations.emergency e"
                + " ON e.id = j.subject_id WHERE e.venue_id = '" + venue + "'")).as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM technical.outbox_event WHERE payload->>'venueId' = '" + venue
                + "' AND event_type IN ('EmergencyDeclared','BookingCancelled','AttendanceResolved')"))
                .as("step %d: no event, so no notification", step).isZero();
        assertThat(dbNow()).isNotNull();
    }
}
