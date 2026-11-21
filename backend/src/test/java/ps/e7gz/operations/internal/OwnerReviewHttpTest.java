package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.operations.internal.EmergencyHttpTest.OWNER;
import static ps.e7gz.operations.internal.EmergencyHttpTest.attendanceOf;
import static ps.e7gz.operations.internal.EmergencyHttpTest.count;
import static ps.e7gz.operations.internal.EmergencyHttpTest.dbNow;
import static ps.e7gz.operations.internal.EmergencyHttpTest.projection;
import static ps.e7gz.operations.internal.EmergencyHttpTest.statusOf;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.operations.api.EmergencyBookings;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-84 (S5-E4) — OWNER_REVIEW_REQUIRED on the real database, with genuine bookings made by their own commands
 * ({@link BookingFlows}): the entry the database's threshold trigger makes at a resource Emergency's end and the system
 * cascade that runs with it (arch 06 section 2.7, FR-OPS-26…FR-OPS-32; J7), the E7-59/E7-60 carry-forward (a late-T4
 * booking starts at startAt, then is interrupted), the E7-69 carry-forward and PO decision 15 Nov 2026 (attendance
 * follows the evidence), and the resolution with the Owner Password (FR-OPS-33, FR-OPS-43; the E7-77 contract D3, D6).
 *
 * <p>The over-threshold history is planted: a Maintenance window of 26 hours that ended four hours ago, so the rolling
 * 48-hour total at the end is over 24 hours (FR-OPS-26). The database decides the rest. Where a test needs a booking that
 * no Emergency cascade could have left live - one that started (or ended) before a Resource Emergency it never overlapped
 * at declaration - the Emergency is planted behind the guards ({@link BookingFlows#resourceEmergencySince}), as the
 * E7-59 tests do.
 */
@AutoConfigureMockMvc
class OwnerReviewHttpTest extends IntegrationTest {

    static final String EMERGENCIES = "/api/v1/venue/emergencies";
    static final String REVIEWS = "/api/v1/venue/owner-reviews";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

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

    // ---------------------------------------------------------------------------------- entry (J7)

    /**
     * J7, FR-OPS-26…FR-OPS-30: END EMERGENCY on an over-threshold room leaves it unsellable under Owner Review, and every
     * commitment of the room - pending, confirmed and a future Manual Booking - is CANCELLED under
     * RESOURCE_OWNER_REVIEW_REQUIRED in the same commit, by the system, with no Owner Password.
     */
    @Test
    void endingAnOverThresholdResourceEmergencyEntersOwnerReviewAndCancelsEveryCommitmentOfTheRoom() throws Exception {
        List<BookingFlows.Booking> two = flows.twoPendingOfOneCustomer();
        BookingFlows.Booking confirmed = two.get(0);
        BookingFlows.Booking pending = two.get(1);
        flows.accept(confirmed);
        UUID manual = flows.manualOnRoomOf(confirmed, pending.startAt().plus(Duration.ofHours(2)), 60);
        UUID emergency = declareResource(confirmed, dbNow().plus(Duration.ofMinutes(20)));
        assertThat(statusOf(pending.bookingId())).as("outside the window: waiting, paused").isEqualTo("PENDING/-");
        overThreshold(confirmed.resourceId());

        end(confirmed, emergency).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDED"));

        UUID room = confirmed.resourceId();
        assertThat(restriction(room)).as("unsellable: owner review over availability (FR-OPS-27)")
                .isEqualTo("OWNER_REVIEW_REQUIRED");
        assertThat(count("SELECT count(*) FROM resource.resource_selling_block WHERE active AND resource_id = '" + room
                + "'")).as("the Emergency's block ended (section 43.2)").isZero();
        assertThat(count("SELECT count(*) FROM operations.owner_review WHERE resource_id = '" + room
                + "' AND resolved_at IS NULL AND NOT owner_password_used AND correlation_id = '" + emergency + "'"))
                .isOne();
        for (UUID booking : List.of(confirmed.bookingId(), pending.bookingId(), manual)) {
            assertThat(statusOf(booking)).isEqualTo("CANCELLED/RESOURCE_OWNER_REVIEW_REQUIRED");
            assertThat(count("SELECT count(*) FROM booking.booking_history WHERE booking_id = '" + booking
                    + "' AND to_status = 'CANCELLED' AND actor_type = 'SYSTEM' AND actor_id IS NULL"))
                    .as("a system consequence (FR-OPS-28)").isOne();
            assertThat(count("SELECT count(*) FROM operations.operational_interruption_record WHERE subject_id = '"
                    + booking + "' AND cause = 'RESOURCE_OWNER_REVIEW_REQUIRED'")).isOne();
        }
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE resource_id = '" + room
                + "' AND active_flag AND reservation_type <> 'MAINTENANCE'")).as("every reservation released").isZero();
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id IN ('"
                + confirmed.bookingId() + "','" + pending.bookingId() + "')")).as("Wallet or Points, never a card")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM operations.booking_timer_pause_history WHERE booking_id = '"
                + pending.bookingId() + "' AND pause_ended_at IS NULL")).as("not a pause state (FR-OPS-30)").isZero();
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE subject_id = '" + pending.bookingId()
                + "' AND type = 'VENUE_RESPONSE_EXPIRY' AND status IN ('PENDING','PAUSED')"))
                .as("its timer stopped: never VENUE_RESPONSE_EXPIRED").isZero();
        assertThat(count("SELECT count(*) FROM technical.outbox_event WHERE event_type = 'BookingCancelled'"
                + " AND payload->>'bookingId' IN ('" + confirmed.bookingId() + "','" + pending.bookingId() + "')"))
                .as("the customers are told after commit").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM technical.outbox_event WHERE event_type = '"
                + IntegrationEvents.RESOURCE_RESTRICTION_CHANGED + "' AND payload->>'resourceId' = '" + room
                + "' AND payload->>'restriction' = 'OWNER_REVIEW_REQUIRED'")).as("the owner is told").isOne();
        assertThat(count("SELECT count(*) FROM admin.admin_alert WHERE kind = 'RESOURCE_OWNER_REVIEW_REQUIRED'"
                + " AND payload->>'resourceId' = '" + room + "'")).as("the Platform Admin is told").isOne();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'OWNER_REVIEW_ENTERED'"
                + " AND actor_type = 'SYSTEM' AND owner_password_used IS NOT TRUE AND context_venue_id = '"
                + confirmed.venueId() + "' AND payload->>'cancelledManualBookings' = '1'"))
                .as("system audit, no Owner Password anywhere (FR-OPS-29)").isOne();
        assertThat(count("SELECT count(*) FROM trust.cascade_execution WHERE cascade_type = 'OWNER_REVIEW'"
                + " AND source_id = (SELECT id FROM operations.owner_review WHERE resource_id = '" + room + "')"))
                .as("the database's once-only guard, written at commit").isOne();
        assertThat(count("SELECT count(*) FROM trust.trust_event WHERE booking_id IN ('" + confirmed.bookingId()
                + "','" + pending.bookingId() + "')")).as("no trust effect (FR-OPS-31)").isZero();

        mvc.perform(get(REVIEWS).header("Authorization", venue(confirmed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].resourceId").value(room.toString()))
                .andExpect(jsonPath("$.data[0].resolvedAt").isEmpty())
                .andExpect(jsonPath("$.data[0].resolution").isEmpty());
    }

    /** FR-OPS-26: under the threshold, END returns the room to sale as before - no review, nothing cancelled. */
    @Test
    void endingUnderTheThresholdEntersNoReview() throws Exception {
        BookingFlows.Booking pending = flows.pending();
        UUID emergency = declareResource(pending, dbNow().plus(Duration.ofMinutes(20)));

        end(pending, emergency).andExpect(status().isOk());

        assertThat(restriction(pending.resourceId())).isEqualTo("NONE");
        assertThat(count("SELECT count(*) FROM operations.owner_review WHERE resource_id = '" + pending.resourceId()
                + "'")).isZero();
        assertThat(statusOf(pending.bookingId())).isEqualTo("PENDING/-");
    }

    /** FR-OPS-26 "expected or actual end": the expected end passing enters the review and runs the same cascade. */
    @Test
    void theExpectedEndPassingOverTheThresholdEntersTheReviewAndItsCascade() throws Exception {
        BookingFlows.Booking pending = flows.pending();
        UUID emergency = declareResource(pending, dbNow().plus(Duration.ofMinutes(20)));
        overThreshold(pending.resourceId());
        Instant expectedEnd = moveExpectedEndIntoThePast(emergency);

        LockSet locks = runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), ctx -> {
            LockSet.Builder builder = LockSet.builder().venues(pending.venueId()).resources(pending.resourceId());
            EmergencyCommands.ownerReviewLocks(ctx, emergencies.deps(),
                    EmergencyCommands.read(ctx, pending.venueId(), emergency).orElseThrow(), builder);
            return builder.build();
        }));
        EmergencyCommands.ExpectedEndOutcome outcome = runner.run(TestCommands.under(CommandScope.shared(locks),
                ctx -> EmergencyCommands.expectedEnd(ctx, emergencies.deps(), emergency, expectedEnd, locks)));
        assertThat(outcome).isEqualTo(EmergencyCommands.ExpectedEndOutcome.DONE);

        assertThat(OWNER.sql("SELECT status FROM operations.emergency WHERE id = :id").param("id", emergency)
                .query(String.class).single()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(restriction(pending.resourceId())).isEqualTo("OWNER_REVIEW_REQUIRED");
        assertThat(statusOf(pending.bookingId())).isEqualTo("CANCELLED/RESOURCE_OWNER_REVIEW_REQUIRED");

        // the actual end later enters nothing new: the review and its cascade are not repeated
        end(pending, emergency).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM operations.owner_review WHERE resource_id = '" + pending.resourceId()
                + "'")).isOne();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'OWNER_REVIEW_ENTERED'"
                + " AND context_venue_id = '" + pending.venueId() + "'")).isOne();
    }

    /** FR-OPS-28, FR-OPS-44: an AWAITING booking is cancelled under the review's reason; its Late Confirmation closes. */
    @Test
    void anAwaitingBookingOfTheRoomIsCancelledAndItsLateConfirmationClosed() throws Exception {
        BookingFlows.Booking awaiting = flows.lateConfirmed();
        UUID emergency = declareResource(awaiting, dbNow().plus(Duration.ofMinutes(20)));
        overThreshold(awaiting.resourceId());

        end(awaiting, emergency).andExpect(status().isOk());

        assertThat(statusOf(awaiting.bookingId())).isEqualTo("CANCELLED/RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(count("SELECT count(*) FROM booking.late_confirmation WHERE booking_id = '" + awaiting.bookingId()
                + "' AND closed_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = '"
                + awaiting.bookingId() + "' AND active_flag")).isZero();
    }

    /**
     * FR-OPS-32, Spec v5 section 43.6: an expired request holds no reservation and stays VENUE_RESPONSE_EXPIRED, but loses
     * Late Confirm eligibility, recorded as a Booking Event with its detail code.
     */
    @Test
    void anExpiredRequestStaysExpiredAndRecordsTheLostLateConfirmEligibility() throws Exception {
        BookingFlows.Booking expired = flows.pending();
        flows.expire(expired);
        UUID emergency = declareResource(expired, dbNow().plus(Duration.ofMinutes(20)));
        overThreshold(expired.resourceId());

        end(expired, emergency).andExpect(status().isOk());

        assertThat(statusOf(expired.bookingId())).isEqualTo("VENUE_RESPONSE_EXPIRED/-");
        assertThat(count("SELECT count(*) FROM booking.booking_history WHERE booking_id = '" + expired.bookingId()
                + "' AND entry_kind = 'BOOKING_EVENT'"
                + " AND event_type = 'LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED'"
                + " AND detail_code = 'RESOURCE_OWNER_REVIEW_REQUIRED' AND actor_type = 'SYSTEM'")).isOne();
    }

    // ---------------------------------------------------------------------------------- carry-forwards

    /**
     * E7-59/E7-60 carry-forward (PO decisions 19-20 Oct 2026, Spec v5 section 31.7): a booking still CONFIRMED after its
     * startAt because T4 was late starts at startAt, then is interrupted at the review's own time. Through an Emergency
     * boundary such a booking can only be one whose time has already passed (any Emergency that overlapped it ended it at
     * declaration - see the next test), so the entry is made here as the database's trigger makes it, in the same
     * transaction, after the late start ({@link EmergencyBookings#startOverdue}, run before every boundary).
     */
    @Test
    void aLateStartBookingIsStartedAtItsStartThenInterruptedAtTheReviewsTime() {
        BookingFlows.Booking late = flows.confirmedWithOverdueStart(Duration.ofMinutes(10));
        EmergencyBookings bookings = emergencies.deps().bookings();
        LockSet locks = LockSet.builder().venues(late.venueId()).resources(late.resourceId()).users(late.customerId())
                .build();

        Instant enteredAt = runner.run(TestCommands.under(CommandScope.shared(locks), ctx -> {
            assertThat(bookings.startOverdue(ctx, late.venueId(), late.resourceId())).containsExactly(late.bookingId());
            enterReviewAsTheTriggerDoes(ctx, late.resourceId());
            List<EmergencyBookings.Outcome> outcomes = bookings.endForOwnerReview(ctx, late.venueId(),
                    List.of(late.bookingId()));
            assertThat(outcomes).singleElement().satisfies(o -> {
                assertThat(o.from()).isEqualTo(BookingStatus.ACTIVE);
                assertThat(o.reason()).isEqualTo(BookingTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED);
                assertThat(o.completed()).isFalse();
            });
            return ctx.commandNow();
        }));

        assertThat(statusOf(late.bookingId())).isEqualTo("CANCELLED/RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(transitions(late.bookingId(), late.startAt(), null))
                .containsSubsequence("PENDING>CONFIRMED@other", "CONFIRMED>ACTIVE@startAt", "ACTIVE>CANCELLED@other")
                .endsWith("ACTIVE>CANCELLED@other");
        Instant interruptedAt = OWNER.sql("SELECT terminal_at FROM booking.booking WHERE id = :id")
                .param("id", late.bookingId()).query(OffsetDateTime.class).single().toInstant();
        assertThat(interruptedAt).as("at the review's own time").isEqualTo(enteredAt).isAfter(late.startAt());
        assertThat(attendanceOf(late.bookingId())).isEqualTo("NOT_APPLICABLE");
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE subject_id = '" + late.bookingId()
                + "' AND type IN ('BOOKING_START','BOOKING_COMPLETE') AND status = 'PENDING'")).isZero();
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = '" + late.bookingId()
                + "'")).as("a full refund").isOne();
    }

    /**
     * The same carry-forward through a real END EMERGENCY: a booking whose T4 and T5 were both late and whose time had
     * passed before the Emergency was declared (so the declaration never reached it) starts at startAt and completes at
     * endAt, as its timers would have - it is never interrupted after its end.
     */
    @Test
    void aLateStartBookingWhoseTimePassedIsStartedAtItsStartThenCompletedAtItsEnd() throws Exception {
        BookingFlows.Booking late = flows.confirmedWithOverdueStart(Duration.ofMinutes(70));
        Instant endAt = BookingFlows.endAt(late.bookingId());
        assertThat(endAt).as("its time has passed").isBefore(dbNow());
        UUID emergency = declareResource(late, dbNow().plus(Duration.ofMinutes(20)));
        assertThat(statusOf(late.bookingId())).as("the declaration never reached it").isEqualTo("CONFIRMED/-");
        overThreshold(late.resourceId());

        end(late, emergency).andExpect(status().isOk());

        assertThat(statusOf(late.bookingId())).isEqualTo("COMPLETED/-");
        assertThat(transitions(late.bookingId(), late.startAt(), endAt))
                .containsSubsequence("PENDING>CONFIRMED@other", "CONFIRMED>ACTIVE@startAt", "ACTIVE>COMPLETED@endAt")
                .endsWith("ACTIVE>COMPLETED@endAt");
        assertThat(attendanceOf(late.bookingId())).isEqualTo("ATTENDANCE_UNCONFIRMED");
        assertThat(restriction(late.resourceId())).isEqualTo("OWNER_REVIEW_REQUIRED");
        assertThat(count("SELECT count(*) FROM operations.operational_interruption_record WHERE subject_id = '"
                + late.bookingId() + "'")).isZero();
    }

    /**
     * E7-69 carry-forward, PO decision 15 Nov 2026 (FR-ATT-10 over FR-OPS-31 for this case): a checked-in ACTIVE booking
     * the review cancels is ATTENDED_INTERRUPTED through BookingOutcomes.recordInterruption - the counter resets - with
     * no Verified Player progress, no points and a full refund.
     */
    @Test
    void aCheckedInActiveBookingIsAttendedInterruptedAndResetsTheNoShowCounter() throws Exception {
        BookingFlows.Customer customer = flows.customer();
        BookingFlows.Booking missed = customer.nextActive(Duration.ofMinutes(20));
        attendance.noShow(missed.venueAccountId(), missed.bookingId());
        assertThat(projection(missed.customerId(), "consecutive_no_shows")).isOne();
        long attendedBefore = projection(missed.customerId(), "completed_attended_count");
        BookingFlows.Booking active = customer.nextActive(Duration.ofMinutes(5));
        attendance.checkIn(active.venueAccountId(), active.bookingId());
        UUID emergency = plantedEmergencySince(active, dbNow().minus(Duration.ofMinutes(1)));
        overThreshold(active.resourceId());

        end(active, emergency).andExpect(status().isOk());

        assertThat(statusOf(active.bookingId())).isEqualTo("CANCELLED/RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(attendanceOf(active.bookingId())).isEqualTo("ATTENDED_INTERRUPTED");
        assertThat(count("SELECT count(*) FROM trust.trust_event WHERE booking_id = '" + active.bookingId()
                + "' AND event_type = 'BOOKING_ATTENDED_INTERRUPTED'")).isOne();
        assertThat(projection(active.customerId(), "consecutive_no_shows")).as("the counter resets").isZero();
        assertThat(projection(active.customerId(), "completed_attended_count")).isEqualTo(attendedBefore);
        assertThat(count("SELECT count(*) FROM loyalty.points_transaction WHERE booking_id = '" + active.bookingId()
                + "'")).as("no points").isZero();
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = '" + active.bookingId()
                + "'")).as("a full refund").isOne();
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = '"
                + active.bookingId() + "' AND active_flag")).as("released at the interruption").isZero();
    }

    /** FR-OPS-31: without evidence the interrupted booking is NOT_APPLICABLE, with no Trust effect. */
    @Test
    void anActiveBookingWithoutEvidenceIsNotApplicableWithNoTrustEffect() throws Exception {
        BookingFlows.Booking active = flows.active(Duration.ofMinutes(5));
        UUID emergency = plantedEmergencySince(active, dbNow().minus(Duration.ofMinutes(1)));
        overThreshold(active.resourceId());

        end(active, emergency).andExpect(status().isOk());

        assertThat(statusOf(active.bookingId())).isEqualTo("CANCELLED/RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(attendanceOf(active.bookingId())).isEqualTo("NOT_APPLICABLE");
        assertThat(count("SELECT count(*) FROM trust.trust_event WHERE booking_id = '" + active.bookingId() + "'"))
                .isZero();
    }

    /** FR-ATT-10: Late Present only is ATTENDANCE_UNCONFIRMED, the counter unchanged. */
    @Test
    void anActiveBookingWithLatePresentOnlyIsUnconfirmed() throws Exception {
        BookingFlows.Booking active = flows.active(Duration.ofMinutes(20));
        attendance.latePresent(active.venueAccountId(), active.bookingId());
        UUID emergency = plantedEmergencySince(active, dbNow().minus(Duration.ofMinutes(1)));
        overThreshold(active.resourceId());

        end(active, emergency).andExpect(status().isOk());

        assertThat(attendanceOf(active.bookingId())).isEqualTo("ATTENDANCE_UNCONFIRMED");
        assertThat(count("SELECT count(*) FROM trust.trust_event WHERE booking_id = '" + active.bookingId() + "'"))
                .isZero();
    }

    /**
     * A session already over when the review starts (T5 late) is not interrupted after its end: it completes as of its
     * endAt, as T5 would have - no cancellation, no refund, no interruption record.
     */
    @Test
    void anActiveBookingPastItsEndIsCompletedAsOfItsEndNotInterrupted() throws Exception {
        BookingFlows.Booking active = flows.pastItsEnd(flows.active(Duration.ofMinutes(5)), Duration.ofMinutes(5));
        UUID emergency = plantedEmergencySince(active, dbNow().minus(Duration.ofMinutes(1)));
        overThreshold(active.resourceId());

        end(active, emergency).andExpect(status().isOk());

        assertThat(statusOf(active.bookingId())).isEqualTo("COMPLETED/-");
        assertThat(attendanceOf(active.bookingId())).isEqualTo("ATTENDANCE_UNCONFIRMED");
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = '" + active.bookingId()
                + "'")).isZero();
        assertThat(count("SELECT count(*) FROM operations.operational_interruption_record WHERE subject_id = '"
                + active.bookingId() + "'")).isZero();
        assertThat(restriction(active.resourceId())).isEqualTo("OWNER_REVIEW_REQUIRED");
    }

    // ---------------------------------------------------------------------------------- resolution (FR-OPS-33)

    /**
     * FR-OPS-33, D6: Return to Service with the Owner Password clears the restriction and closes the review, audited
     * with the check; resolving it again is OPERATIONAL_STATE_CHANGED.
     */
    @Test
    void returnToServiceClearsTheRestrictionWithTheOwnerPasswordAndASecondResolutionIsStateChanged()
            throws Exception {
        BookingFlows.Booking at = underReview();

        resolve(at, "{\"action\":\"RETURN_TO_SERVICE\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceId").value(at.resourceId().toString()))
                .andExpect(jsonPath("$.data.resolution").value("RETURN_TO_SERVICE"))
                .andExpect(jsonPath("$.data.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.ownerPassword").doesNotExist());

        assertThat(restriction(at.resourceId())).isEqualTo("NONE");
        assertThat(count("SELECT count(*) FROM operations.owner_review WHERE resource_id = '" + at.resourceId()
                + "' AND resolution = 'RETURN_TO_SERVICE' AND owner_password_used")).isOne();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'OWNER_REVIEW_RESOLVED'"
                + " AND actor_type = 'VENUE' AND owner_password_used AND owner_password_outcome = 'SUCCESS'"
                + " AND context_venue_id = '" + at.venueId() + "'")).isOne();
        assertThat(count("SELECT count(*) FROM resource.resource_state_change WHERE resource_id = '" + at.resourceId()
                + "' AND from_restriction = 'OWNER_REVIEW_REQUIRED' AND to_restriction = 'NONE'"
                + " AND owner_password_used AND actor_type = 'VENUE'")).isOne();
        assertThat(count("SELECT count(*) FROM technical.outbox_event WHERE event_type = '"
                + IntegrationEvents.RESOURCE_RESTRICTION_CHANGED + "' AND payload->>'resourceId' = '"
                + at.resourceId() + "' AND payload->>'restriction' = 'NONE'")).as("availability refreshes").isOne();

        resolve(at, "{\"action\":\"RETURN_TO_SERVICE\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
        mvc.perform(get(REVIEWS).header("Authorization", venue(at)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    /** FR-OPS-33, ADR-011: the Owner Password is always needed - absent is REQUIRED, wrong is INVALID, nothing changes. */
    @Test
    void resolvingWithoutOrWithAWrongOwnerPasswordChangesNothing() throws Exception {
        BookingFlows.Booking at = underReview();

        resolve(at, "{\"action\":\"RETURN_TO_SERVICE\"}")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        resolve(at, "{\"action\":\"RETURN_TO_SERVICE\",\"ownerPassword\":\"0000\"}")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));

        assertThat(restriction(at.resourceId())).isEqualTo("OWNER_REVIEW_REQUIRED");
        assertThat(count("SELECT count(*) FROM operations.owner_review WHERE resource_id = '" + at.resourceId()
                + "' AND resolved_at IS NULL")).isOne();
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'OWNER_PASSWORD_REFUSED'"
                + " AND subject_id = '" + at.venueAccountId() + "' AND payload->>'protectedAction' = 'RESOLVE_OWNER_REVIEW'"))
                .as("the failed attempt is audited under the action").isPositive();
    }

    /**
     * Section 43.8: Return to Service is blocked while Maintenance is in force; the 422 names what blocks it and nothing
     * changes (FR-OPS-43).
     */
    @Test
    void returnToServiceIsBlockedWhileMaintenanceIsInForceAndNamesIt() throws Exception {
        BookingFlows.Booking at = underReview();
        UUID maintenance = plantMaintenance(at.resourceId(), "clock_timestamp() - interval '10 minutes'",
                "clock_timestamp() + interval '2 hours'");

        resolve(at, "{\"action\":\"RETURN_TO_SERVICE\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OWNER_REVIEW_RESOLUTION_BLOCKED"))
                .andExpect(jsonPath("$.error.details.blockingReservations.length()").value(1))
                .andExpect(jsonPath("$.error.details.blockingReservations[0].bookingId").value(maintenance.toString()))
                .andExpect(jsonPath("$.error.details.blockingReservations[0].kind").value("MAINTENANCE"))
                .andExpect(jsonPath("$.error.details.blockingReservations[0].period").isNotEmpty());

        assertThat(restriction(at.resourceId())).isEqualTo("OWNER_REVIEW_REQUIRED");
    }

    /**
     * FR-OPS-33: Extend Maintenance opens a Maintenance window from now to the new end, authorised by this Owner
     * Password over the rolling threshold, and clears the restriction - the window keeps the room unsellable.
     */
    @Test
    void extendMaintenanceOpensAWindowFromNowAndClearsTheRestriction() throws Exception {
        BookingFlows.Booking at = underReview();
        Instant until = dbNow().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.MINUTES);

        resolve(at, "{\"action\":\"EXTEND_MAINTENANCE\",\"maintenance\":{\"endAt\":\"" + until
                + "\",\"reason\":\"Waiting for a part\"},\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("EXTEND_MAINTENANCE"));

        assertThat(restriction(at.resourceId())).isEqualTo("NONE");
        assertThat(count("SELECT count(*) FROM operations.maintenance_window WHERE resource_id = '" + at.resourceId()
                + "' AND upper(period) = '" + OffsetDateTime.ofInstant(until, ZoneOffset.UTC) + "'"
                + " AND owner_password_used AND deleted_at IS NULL")).isOne();
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE resource_id = '" + at.resourceId()
                + "' AND active_flag AND reservation_type = 'MAINTENANCE' AND planned_end = '"
                + OffsetDateTime.ofInstant(until, ZoneOffset.UTC) + "'")).as("the window blocks sales").isOne();
    }

    /** The contract: maintenance goes with EXTEND_MAINTENANCE only (VALIDATION_FAILED), after the Owner Password. */
    @Test
    void maintenanceWithTheWrongActionOrNoneWithExtendIsAValidationFailure() throws Exception {
        BookingFlows.Booking at = underReview();

        resolve(at, "{\"action\":\"EXTEND_MAINTENANCE\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        resolve(at, "{\"action\":\"DISABLE\",\"maintenance\":{\"endAt\":\"" + dbNow().plus(Duration.ofHours(2))
                + "\",\"reason\":\"x\"},\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(restriction(at.resourceId())).isEqualTo("OWNER_REVIEW_REQUIRED");
    }

    /** FR-OPS-33: Disable moves the room to DISABLED through the resource's own rule and clears the restriction. */
    @Test
    void disableMovesTheRoomToDisabledAndClearsTheRestriction() throws Exception {
        BookingFlows.Booking at = underReview();

        resolve(at, "{\"action\":\"DISABLE\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolution").value("DISABLE"));

        assertThat(OWNER.sql("SELECT base_state || '/' || operational_restriction FROM resource.resource WHERE id = :id")
                .param("id", at.resourceId()).query(String.class).single()).isEqualTo("DISABLED/NONE");
    }

    /**
     * FR-OPS-43: Archive waits while a future protected reservation remains - a future Maintenance window here - and the
     * 422 names it.
     */
    @Test
    void archiveIsBlockedByAFutureReservationAndNamesIt() throws Exception {
        BookingFlows.Booking at = underReview();
        UUID future = plantMaintenance(at.resourceId(), "clock_timestamp() + interval '4 hours'",
                "clock_timestamp() + interval '5 hours'");

        resolve(at, "{\"action\":\"ARCHIVE\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("OWNER_REVIEW_RESOLUTION_BLOCKED"))
                .andExpect(jsonPath("$.error.details.blockingReservations[0].bookingId").value(future.toString()));
        assertThat(OWNER.sql("SELECT base_state || '/' || operational_restriction FROM resource.resource WHERE id = :id")
                .param("id", at.resourceId()).query(String.class).single()).isEqualTo("ACTIVE/OWNER_REVIEW_REQUIRED");
    }

    /** The Idempotency-Key is optional; sent, a replay answers as stored instead of OPERATIONAL_STATE_CHANGED. */
    @Test
    void aReplayedResolutionWithTheSameKeyAnswersAsStored() throws Exception {
        BookingFlows.Booking at = underReview();
        UUID key = UUID.randomUUID();
        String body = "{\"action\":\"RETURN_TO_SERVICE\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}";

        String first = resolve(at, body, key).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String again = resolve(at, body, key).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(json.readTree(again).path("data")).isEqualTo(json.readTree(first).path("data"));
    }

    @Test
    void anotherVenuesRoomIsNotFoundAndARoomWithNoOpenReviewIsStateChanged() throws Exception {
        BookingFlows.Booking at = underReview();
        BookingFlows.Booking other = flows.pending();
        flows.ownerPin(other.venueAccountId());

        resolve(other, at.resourceId(), "{\"action\":\"RETURN_TO_SERVICE\",\"ownerPassword\":\""
                + BookingFlows.OWNER_PIN + "\"}", null)
                .andExpect(status().isNotFound());
        resolve(other, "{\"action\":\"RETURN_TO_SERVICE\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
    }

    // ---------------------------------------------------------------------------------- admin queue (D3)

    /** D3: the Platform Admin's read-only queue - open by default, resolved on request, by venue, cursor paged. */
    @Test
    void theAdminQueueListsOpenAndResolvedReviewsReadOnly() throws Exception {
        BookingFlows.Booking first = underReview();
        BookingFlows.Booking second = underReview();
        String admin = "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, Fixtures.anAdmin().create().id());

        mvc.perform(get("/api/v1/admin/owner-reviews").header("Authorization", admin)
                        .param("venueId", first.venueId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].resourceId").value(first.resourceId().toString()))
                .andExpect(jsonPath("$.data.items[0].venueName").value(first.venueName()))
                .andExpect(jsonPath("$.data.items[0].resourceName").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].resolvedAt").isEmpty());

        resolve(second, "{\"action\":\"RETURN_TO_SERVICE\",\"ownerPassword\":\"" + BookingFlows.OWNER_PIN + "\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/owner-reviews").header("Authorization", admin)
                        .param("venueId", second.venueId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/v1/admin/owner-reviews").header("Authorization", admin)
                        .param("venueId", second.venueId().toString()).param("resolved", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].resolution").value("RETURN_TO_SERVICE"));

        JsonNode page = data(mvc.perform(get("/api/v1/admin/owner-reviews").header("Authorization", admin)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1)));
        assertThat(page.path("nextCursor").isNull()).as("more than one open review exists").isFalse();

        mvc.perform(post("/api/v1/admin/owner-reviews").header("Authorization", admin))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 404, 405));
    }

    // ---------------------------------------------------------------------------------- helpers

    /** A room under Owner Review, entered by END EMERGENCY over the threshold; its venue account has the Owner PIN. */
    BookingFlows.Booking underReview() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        flows.ownerPin(booking.venueAccountId());
        UUID emergency = declareResource(booking, dbNow().plus(Duration.ofMinutes(20)));
        overThreshold(booking.resourceId());
        end(booking, emergency).andExpect(status().isOk());
        assertThat(restriction(booking.resourceId())).isEqualTo("OWNER_REVIEW_REQUIRED");
        return booking;
    }

    UUID declareResource(BookingFlows.Booking at, Instant expectedEnd) throws Exception {
        return UUID.fromString(data(mvc.perform(write(post(EMERGENCIES + "/resource-outage"), at,
                        "{\"resourceId\":\"" + at.resourceId() + "\",\"reason\":\"Console overheating\","
                                + "\"expectedReturnAt\":\"" + expectedEnd + "\"}", UUID.randomUUID()))
                .andExpect(status().isCreated())).path("id").asText());
    }

    ResultActions end(BookingFlows.Booking at, UUID emergency) throws Exception {
        return mvc.perform(write(post(EMERGENCIES + "/" + emergency + "/end"), at, "{}", UUID.randomUUID()));
    }

    ResultActions resolve(BookingFlows.Booking at, String body) throws Exception {
        return resolve(at, at.resourceId(), body, null);
    }

    ResultActions resolve(BookingFlows.Booking at, String body, UUID key) throws Exception {
        return resolve(at, at.resourceId(), body, key);
    }

    ResultActions resolve(BookingFlows.Booking at, UUID resourceId, String body, UUID key) throws Exception {
        MockHttpServletRequestBuilder request = post(REVIEWS + "/" + resourceId + "/resolve")
                .header("Authorization", venue(at)).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key.toString());
        }
        return mvc.perform(request);
    }

    /** The planted Emergency's id: declared at {@code declaredAt}, ACTIVE, with its block (behind the guards). */
    UUID plantedEmergencySince(BookingFlows.Booking at, Instant declaredAt) {
        flows.resourceEmergencySince(at, declaredAt);
        return OWNER.sql("SELECT id FROM operations.emergency WHERE resource_id = :room AND status = 'ACTIVE'")
                .param("room", at.resourceId()).query(UUID.class).single();
    }

    /** 26 hours of Maintenance that ended four hours ago: the rolling 48-hour total is over 24 hours (FR-OPS-26). */
    static void overThreshold(UUID room) {
        plantMaintenance(room, "clock_timestamp() - interval '30 hours'", "clock_timestamp() - interval '4 hours'");
    }

    /** A live Maintenance window over {@code [start, end)} (SQL expressions) with its ledger row, behind the guards. */
    static UUID plantMaintenance(UUID room, String start, String end) {
        UUID id = UUID.randomUUID();
        withoutTriggers(jdbc -> {
            jdbc.sql("INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)"
                            + " VALUES (:id, :room, tstzrange(" + start + ", " + end + ", '[)'), 'Repeated faults',"
                            + " true)")
                    .param("id", id).param("room", room).update();
            jdbc.sql("""
                            INSERT INTO inventory.resource_reservation (id, resource_id, reservation_type, maintenance_id,
                                                                        planned_start, planned_end, planned_range,
                                                                        actual_reservation_start, active_flag)
                            SELECT gen_random_uuid(), m.resource_id, 'MAINTENANCE', m.id, lower(m.period),
                                   upper(m.period), m.period, lower(m.period), true
                              FROM operations.maintenance_window m WHERE m.id = :id""")
                    .param("id", id).update();
        });
        return id;
    }

    static void withoutTriggers(Consumer<JdbcClient> work) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            work.accept(jdbc);
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }

    /** As {@link EmergencyHttpTest#moveExpectedEndIntoThePast}. */
    Instant moveExpectedEndIntoThePast(UUID id) {
        Instant end = dbNow().minus(Duration.ofSeconds(1)).truncatedTo(ChronoUnit.MILLIS);
        withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE operations.emergency
                           SET expected_end_at = CAST(:end AS timestamptz),
                               declared_at = LEAST(declared_at, CAST(:end AS timestamptz) - interval '1 minute'),
                               declared_period = tstzrange(LEAST(declared_at, CAST(:end AS timestamptz)
                                                                 - interval '1 minute'),
                                                           CAST(:end AS timestamptz), '[)')
                         WHERE id = :id""")
                .param("end", OffsetDateTime.ofInstant(end, ZoneOffset.UTC)).param("id", id).update());
        return OWNER.sql("SELECT expected_end_at FROM operations.emergency WHERE id = :id").param("id", id)
                .query(OffsetDateTime.class).single().toInstant();
    }

    /** The booking's status transitions as {@code from>to@startAt|endAt|other}, in order. */
    static List<String> transitions(UUID bookingId, Instant startAt, Instant endAt) {
        return OWNER.sql("""
                        SELECT from_status || '>' || to_status || '@' ||
                               CASE WHEN occurred_at = :start THEN 'startAt'
                                    WHEN occurred_at = CAST(:end AS timestamptz) THEN 'endAt' ELSE 'other' END
                          FROM booking.booking_history WHERE booking_id = :id AND entry_kind = 'STATUS_TRANSITION'
                         ORDER BY sequence""")
                .param("id", bookingId).param("start", OffsetDateTime.ofInstant(startAt, ZoneOffset.UTC))
                .param("end", endAt == null ? null : OffsetDateTime.ofInstant(endAt, ZoneOffset.UTC))
                .query(String.class).list();
    }

    /**
     * The Owner Review entered now on the command's transaction, exactly as
     * {@code operations.apply_owner_review_threshold_on_emergency_boundary} enters it: the restriction, its SYSTEM state
     * change, the open review.
     */
    static void enterReviewAsTheTriggerDoes(ps.e7gz.common.command.CommandContext ctx, UUID room) {
        OffsetDateTime now = OffsetDateTime.ofInstant(ctx.commandNow(), ZoneOffset.UTC);
        UUID correlation = UUID.randomUUID();
        ctx.jdbc().sql("UPDATE resource.resource SET operational_restriction = 'OWNER_REVIEW_REQUIRED' WHERE id = :room")
                .param("room", room).update();
        ctx.jdbc().sql("""
                        INSERT INTO resource.resource_state_change (id, resource_id, from_restriction, to_restriction,
                                                                    owner_password_used, actor_type, reason,
                                                                    correlation_id, changed_at)
                        VALUES (gen_random_uuid(), :room, 'NONE', 'OWNER_REVIEW_REQUIRED', false, 'SYSTEM',
                                'MAINTENANCE_THRESHOLD_EXCEEDED_24H_IN_ROLLING_48H', :correlation, :now)""")
                .param("room", room).param("correlation", correlation).param("now", now).update();
        ctx.jdbc().sql("""
                        INSERT INTO operations.owner_review (id, resource_id, entered_at, entry_cause,
                                                             owner_password_used, correlation_id)
                        VALUES (gen_random_uuid(), :room, :now, 'MAINTENANCE_THRESHOLD_EXCEEDED_24H_IN_ROLLING_48H',
                                false, :correlation)""")
                .param("room", room).param("now", now).param("correlation", correlation).update();
    }

    static String restriction(UUID room) {
        return OWNER.sql("SELECT operational_restriction FROM resource.resource WHERE id = :id").param("id", room)
                .query(String.class).single();
    }

    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, BookingFlows.Booking at, String body,
                                        UUID key) {
        return request.header("Authorization", venue(at)).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String venue(BookingFlows.Booking at) {
        return "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, at.venueAccountId());
    }

    JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }
}
