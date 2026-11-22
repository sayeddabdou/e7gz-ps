package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.operations.internal.EmergencyHttpTest.OWNER;
import static ps.e7gz.operations.internal.EmergencyHttpTest.count;
import static ps.e7gz.operations.internal.EmergencyHttpTest.dbNow;
import static ps.e7gz.operations.internal.EmergencyHttpTest.statusOf;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.admin.api.AdminAlerts;
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
 * E7-84 — fault injection at every step of the Owner Review entry (O9; FR-OPS-29, Spec v5 section 43.4: "a partial
 * cascade is not a valid Product outcome"). One successful END EMERGENCY over the threshold counts the steps - every
 * call the command makes to the bookings, Tournaments, scheduler, audit, outbox and admin alerts - then the same end on a
 * fresh world fails at each step in turn, and nothing of it may remain: the Emergency still ACTIVE with its block, the
 * room's restriction NONE, no review, every commitment as it was, no refund decision, no audit row, no alert and no
 * event (so no notification).
 */
class OwnerReviewFaultInjectionTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueEmergencies emergencies;

    private BookingFlows flows;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void flows() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    /** One room over the threshold: a confirmed and a pending booking after an unresolved Emergency's window. */
    private record World(BookingFlows.Booking confirmed, BookingFlows.Booking pending, UUID emergencyId) {
    }

    private World world() {
        List<BookingFlows.Booking> two = flows.twoPendingOfOneCustomer();
        flows.accept(two.get(0));
        BookingFlows.Booking at = two.get(0);
        LockSet locks = runner.run(new EmergencyCommands.Plan(at.venueId(),
                new EmergencyCommands.Subject(at.venueId(), EmergencyScope.RESOURCE, at.resourceId()), null,
                dbNow().plus(Duration.ofMinutes(20)), emergencies.deps()));
        EmergencyCommands.Emergency emergency = runner.run(new EmergencyCommands.DeclareResource(
                new EmergencyCommands.Declaration(new EmergencyCommands.Subject(at.venueId(), EmergencyScope.RESOURCE,
                        at.resourceId()), at.venueAccountId(), CorrelationId.require(), "Overheating",
                        dbNow().plus(Duration.ofMinutes(20)), locks, emergencies.deps())));
        OwnerReviewHttpTest.overThreshold(at.resourceId());
        return new World(two.get(0), two.get(1), emergency.id());
    }

    @Test
    void aFaultAtEveryStepOfTheOwnerReviewEntryLeavesNothingBehind() {
        // E7-79: the shared harness counts the steps, then fails at each one on a fresh world
        FaultInjection.atEveryStep(this::world, this::end, counted -> {
            assertThat(statusOf(counted.confirmed().bookingId())).isEqualTo("CANCELLED/RESOURCE_OWNER_REVIEW_REQUIRED");
            assertThat(statusOf(counted.pending().bookingId())).isEqualTo("CANCELLED/RESOURCE_OWNER_REVIEW_REQUIRED");
        }, 8, OwnerReviewFaultInjectionTest::nothingLeft);
    }

    private void end(World world, Faults faults) {
        Deps real = emergencies.deps();
        EmergencyBookings bookings = faults.wrap(EmergencyBookings.class, real.bookings());
        Outbox outbox = faults.wrap(Outbox.class, real.outbox());
        ScheduledJobs jobs = faults.wrap(ScheduledJobs.class, real.jobs());
        TimerPauses timers = new TimerPauses(bookings, context.getBean(UnresolvedEmergencies.class),
                context.getBean(OperatingSessions.class), context.getBean(VenueEligibility.class),
                context.getBean(ResourceStates.class), jobs, outbox);
        Deps faulty = new Deps(bookings,
                faults.wrap(TournamentCascades.class, real.tournaments()), real.resources(), real.catalog(),
                real.sessions(), real.commitments(), real.baseStates(),
                faults.wrap(AuditWriter.class, real.audit()),
                outbox, jobs, real.gate(), real.closures(), real.json(), timers,
                faults.wrap(AdminAlerts.class, real.alerts()));
        BookingFlows.Booking at = world.confirmed();
        LockSet locks = LockSet.builder().venues(at.venueId()).resources(at.resourceId()).users(at.customerId())
                .build();
        runner.run(new EmergencyCommands.End(at.venueId(), at.venueAccountId(), CorrelationId.require(),
                world.emergencyId(), locks, faulty));
    }

    private static void nothingLeft(World world, int step) {
        UUID room = world.confirmed().resourceId();
        assertThat(OWNER.sql("SELECT status FROM operations.emergency WHERE id = :id").param("id", world.emergencyId())
                .query(String.class).single()).as("step %d: the Emergency unresolved", step).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM resource.resource_selling_block WHERE active AND resource_id = '"
                + room + "'")).as("step %d: its block in force", step).isOne();
        assertThat(OwnerReviewHttpTest.restriction(room)).as("step %d", step).isEqualTo("NONE");
        assertThat(count("SELECT count(*) FROM operations.owner_review WHERE resource_id = '" + room + "'"))
                .as("step %d: no review", step).isZero();
        assertThat(statusOf(world.confirmed().bookingId())).as("step %d", step).isEqualTo("CONFIRMED/-");
        assertThat(statusOf(world.pending().bookingId())).as("step %d", step).isEqualTo("PENDING/-");
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE resource_id = '" + room
                + "' AND active_flag AND reservation_type = 'BOOKING'")).as("step %d: still held", step).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM operations.booking_timer_pause_history WHERE booking_id = '"
                + world.pending().bookingId() + "' AND pause_ended_at IS NULL")).as("step %d: still paused", step)
                .isOne();
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id IN ('"
                + world.confirmed().bookingId() + "','" + world.pending().bookingId() + "')"))
                .as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM operations.operational_interruption_record WHERE subject_id IN ('"
                + world.confirmed().bookingId() + "','" + world.pending().bookingId() + "')"))
                .as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action IN ('OWNER_REVIEW_ENTERED',"
                + "'EMERGENCY_ENDED') AND context_venue_id = '" + world.confirmed().venueId() + "'"))
                .as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM admin.admin_alert WHERE kind = 'RESOURCE_OWNER_REVIEW_REQUIRED'"
                + " AND payload->>'resourceId' = '" + room + "'")).as("step %d", step).isZero();
        assertThat(count("SELECT count(*) FROM technical.outbox_event WHERE payload->>'venueId' = '"
                + world.confirmed().venueId() + "' AND event_type IN ('ResourceRestrictionChanged','EmergencyEnded',"
                + "'BookingCancelled')")).as("step %d: no event, so no notification", step).isZero();
    }
}
