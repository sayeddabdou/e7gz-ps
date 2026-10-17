package ps.e7gz.attendance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.TrustEventType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.trust.api.TrustConsequenceUnavailableException;
import ps.e7gz.trust.api.TrustReconciler;
import ps.e7gz.trust.api.TrustReplay;

/**
 * E7-70 against the real database (arch 07 sections 3.3, 4 and 4.1; FR-TRS-07..09, FR-ATT-09, FR-ATT-13): the Platform
 * Admin's Attendance correction writes the result, its Admin history and audit, the {@code ATTENDANCE_CORRECTED} chain
 * link, the Trust reconciliation and the reward grant or compensation in one transaction, which every database guard
 * accepts at commit. Bookings are completed through the real T5; nothing is planted behind the guards.
 */
class AttendanceCorrectionIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    ApplicationContext context;

    @Autowired
    AttendanceCorrections corrections;

    @Autowired
    TrustReplay replay;

    @Autowired
    TrustReconciler reconciler;

    @Autowired
    CommandRunner runner;

    BookingFlows flows;
    AttendanceFlows attendance;
    CorrelationId.Scope correlation;
    UUID admin;

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
        admin = anAdmin().create().id();
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------- done when (E7-70)

    /**
     * Done when: three chained corrections give exactly one effective outcome (FR-TRS-09). Each correction supersedes
     * the previous leaf, so the chain is linear; replay contributes only the last one, at the booking's own startAt;
     * the reward follows the effective outcome - granted once, compensated once, never doubled (FR-TRS-08, FR-PTS-03).
     */
    @Test
    void threeChainedCorrectionsLeaveExactlyOneEffectiveOutcome() {
        Booking booking = completedUnconfirmed();

        CorrectedAttendance first = corrections.correct(admin, booking.bookingId(), AttendanceResult.ATTENDED, "seen on CCTV");
        assertThat(points(booking.customerId())).isEqualTo(200);
        assertThat(replay.replay(booking.customerId(), null).completedAttendedCount()).isOne();

        CorrectedAttendance second = corrections.correct(admin, booking.bookingId(),
                AttendanceResult.ATTENDANCE_UNCONFIRMED, "CCTV was another day");
        assertThat(points(booking.customerId())).isZero();

        CorrectedAttendance third = corrections.correct(admin, booking.bookingId(),
                AttendanceResult.ATTENDED_INTERRUPTED, "left at the power cut");

        assertThat(third.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(third.previousResult()).isEqualTo(AttendanceResult.ATTENDANCE_UNCONFIRMED);
        assertThat(chain(booking)).containsExactly(
                List.of(first.correctionEventId().toString(), "-", "ATTENDED"),
                List.of(second.correctionEventId().toString(), first.correctionEventId().toString(),
                        "ATTENDANCE_UNCONFIRMED"),
                List.of(third.correctionEventId().toString(), second.correctionEventId().toString(),
                        "ATTENDED_INTERRUPTED"));
        List<TrustReplay.Effect> effects = replay.resolveEffectiveOutcomes(booking.customerId(), null);
        assertThat(effects).hasSize(1);
        assertThat(effects.getFirst().eventId()).isEqualTo(third.correctionEventId());
        assertThat(effects.getFirst().type()).isEqualTo(TrustEventType.BOOKING_ATTENDED_INTERRUPTED);
        assertThat(effects.getFirst().effectiveAt()).isEqualTo(booking.startAt());          // FR-TRS-05
        TrustReplay.State state = replay.replay(booking.customerId(), null);
        assertThat(state.consecutiveNoShows()).isZero();
        assertThat(state.completedAttendedCount()).as("progress follows the effective outcome only").isZero();
        assertThat(projection(booking.customerId())).containsExactly(0, 0);
        assertThat(attendanceResult(booking)).isEqualTo("ATTENDED_INTERRUPTED");
        assertThat(pointsMovements(booking)).containsExactly("ATTENDED_COMPLETION 200", "COMPENSATION -200");
        assertThat(rewardApplications(booking)).as("one application per canonical source, completion included")
                .isEqualTo(4);
        assertThat(adminEvidence(booking)).as("history + audit per correction, with its reason").isEqualTo(3);
        assertThat(status(booking)).isEqualTo("COMPLETED");
        assertThat(correctedEvents(booking)).as("one AttendanceCorrected per Admin command").containsExactly(
                "ATTENDANCE_CORRECTED ATTENDANCE_UNCONFIRMED->ATTENDED COMPLETED",
                "ATTENDANCE_CORRECTED ATTENDED->ATTENDANCE_UNCONFIRMED COMPLETED",
                "ATTENDANCE_CORRECTED ATTENDANCE_UNCONFIRMED->ATTENDED_INTERRUPTED COMPLETED");
    }

    /**
     * Done when: double replay creates nothing twice (NFR-06). Reconciling a settled Customer again - in one command
     * twice, and in a later command - refreshes nothing and writes nothing: the projection version, the ledger, the
     * reward applications and every derived table are unchanged.
     */
    @Test
    void reconcilingASettledCustomerAgainCreatesNothing() {
        Booking booking = completedUnconfirmed();
        corrections.correct(admin, booking.bookingId(), AttendanceResult.ATTENDED, "seen on CCTV");
        UUID customer = booking.customerId();
        List<Long> before = derivedCounts(customer);
        long version = projectionVersion(customer);

        List<TrustReconciler.Reconciliation> again = runner.run(TestCommands.under(scopeOf(booking), command ->
                List.of(reconciler.reconcile(command, customer), reconciler.reconcile(command, customer))));
        TrustReconciler.Reconciliation later = runner.run(TestCommands.under(scopeOf(booking),
                command -> reconciler.reconcile(command, customer)));

        assertThat(again).allSatisfy(found -> {
            assertThat(found.projectionRefreshed()).isFalse();
            assertThat(found.settled()).isTrue();
        });
        assertThat(later.projectionRefreshed()).isFalse();
        assertThat(later.settled()).isTrue();
        assertThat(projectionVersion(customer)).isEqualTo(version);
        assertThat(derivedCounts(customer)).isEqualTo(before);
        assertThat(points(customer)).isEqualTo(200);
    }

    /** A correction away from a checked-in ATTENDED completion compensates the +200 once (arch 07 section 7.4). */
    @Test
    void aCorrectionToANoShowCountsAtTheOriginalPositionAndCompensatesTheReward() {
        Booking played = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(played.venueAccountId(), played.bookingId());
        Booking booking = flows.pastItsEnd(played, Duration.ofMinutes(10));
        assertThat(flows.fireCompletion(booking)).as("T5 settled").isTrue();
        assertThat(points(booking.customerId())).isEqualTo(200);

        CorrectedAttendance corrected = corrections.correct(admin, booking.bookingId(),
                AttendanceResult.ABSENT_NO_SHOW, "checked in the wrong customer");

        assertThat(corrected.status()).as("COMPLETED + ABSENT_NO_SHOW is valid (FR-ATT-09)")
                .isEqualTo(BookingStatus.COMPLETED);
        assertThat(projection(booking.customerId())).containsExactly(1, 0);
        assertThat(points(booking.customerId())).isZero();
        assertThat(pointsMovements(booking)).containsExactly("ATTENDED_COMPLETION 200", "COMPENSATION -200");
        assertThat(replay.resolveEffectiveOutcomes(booking.customerId(), null))
                .extracting(TrustReplay.Effect::type).containsExactly(TrustEventType.BOOKING_NO_SHOW);
    }

    /**
     * The E7-71 boundary: a correction that makes replay cross the stage-0 threshold derives a Ban Qualification. Its
     * operational consequence (Ban Instance + cascade) is E7-71's, not deployed, so the reconciler refuses and the
     * whole correction rolls back - no result change, no event, no audit, no ban fabricated.
     */
    @Test
    void aCorrectionThatQualifiesABanNeedsE771AndUntilThenRollsBackWhole() {
        BookingFlows.Customer customer = flows.customer();
        Booking booking = flows.pastItsEnd(customer.nextActive(Duration.ofMinutes(5)), Duration.ofMinutes(10));
        assertThat(flows.fireCompletion(booking)).as("T5 settled").isTrue();
        for (int i = 0; i < 2; i++) {
            Booking missed = customer.nextActive(Duration.ofMinutes(20));
            attendance.noShow(missed.venueAccountId(), missed.bookingId());
        }
        assertThat(projection(booking.customerId())).containsExactly(2, 0);
        List<Long> before = derivedCounts(booking.customerId());

        assertThatThrownBy(() -> corrections.correct(admin, booking.bookingId(), AttendanceResult.ABSENT_NO_SHOW,
                "was not there"))
                .isInstanceOf(TrustConsequenceUnavailableException.class)
                .hasMessageContaining("E7-71 Ban reconciliation")
                .satisfies(failure -> assertThat(((TrustConsequenceUnavailableException) failure).reconciliation()
                        .outstandingQualifications()).singleElement()
                        .satisfies(due -> assertThat(due.penaltyStage()).isOne()));

        assertThat(attendanceResult(booking)).isEqualTo("ATTENDANCE_UNCONFIRMED");
        assertThat(chain(booking)).isEmpty();
        assertThat(adminEvidence(booking)).isZero();
        assertThat(derivedCounts(booking.customerId())).isEqualTo(before);
        assertThat(projection(booking.customerId())).containsExactly(2, 0);
        assertThat(correctedEvents(booking)).as("rolled back with the command").isEmpty();
    }

    /**
     * Two Admins correcting one booking at once are serialised by the Customer lock: both commit, the second
     * supersedes the first, the chain never branches and replay still has one effective outcome.
     */
    @Test
    void concurrentCorrectionsChainInsteadOfBranching() {
        Booking booking = completedUnconfirmed();
        UUID otherAdmin = anAdmin().create().id();
        List<AttendanceResult> outcomes = List.of(AttendanceResult.ATTENDED, AttendanceResult.ATTENDED_INTERRUPTED);

        ConcurrentRace.Result<CorrectedAttendance> race = ConcurrentRace.run(2, Duration.ofSeconds(30),
                (racer, start) -> {
                    try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                        start.await();
                        return corrections.correct(racer == 0 ? admin : otherAdmin, booking.bookingId(),
                                outcomes.get(racer), "racer " + racer);
                    }
                });

        assertThat(race.failures()).isEmpty();
        List<List<String>> chain = chain(booking);
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).get(1)).isEqualTo("-");
        assertThat(chain.get(1).get(1)).isEqualTo(chain.get(0).get(0));
        String winner = chain.get(1).get(2);
        assertThat(attendanceResult(booking)).isEqualTo(winner);
        assertThat(replay.resolveEffectiveOutcomes(booking.customerId(), null)).singleElement()
                .satisfies(effect -> assertThat(effect.eventId().toString()).isEqualTo(chain.get(1).get(0)));
        assertThat(points(booking.customerId())).isEqualTo(winner.equals("ATTENDED") ? 200 : 0);
        assertThat(correctedEvents(booking)).as("one event per committed command").hasSize(2);
    }

    // ---------------------------------------------------------------------------------------------------- refusals

    @Test
    void refusalsWriteNothing() {
        Booking completed = completedUnconfirmed();
        assertRefused(completed, AttendanceResult.ATTENDED, " ", ApiErrorCode.VALIDATION_FAILED);
        assertRefused(completed, AttendanceResult.NOT_APPLICABLE, "reason", ApiErrorCode.VALIDATION_FAILED);
        assertRefused(completed, AttendanceResult.ATTENDANCE_UNCONFIRMED, "same result",
                ApiErrorCode.BOOKING_STATE_CHANGED);
        assertThatThrownBy(() -> corrections.correct(admin, UUID.randomUUID(), AttendanceResult.ATTENDED, "r"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(ApiErrorCode.NOT_FOUND);

        Booking active = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(active.venueAccountId(), active.bookingId());
        assertRefused(active, AttendanceResult.ATTENDED, "not resolved yet", ApiErrorCode.BOOKING_STATE_CHANGED);

        Booking missed = flows.active(Duration.ofMinutes(20));
        attendance.noShow(missed.venueAccountId(), missed.bookingId());
        assertRefused(missed, AttendanceResult.ATTENDED, "reverse first (FR-ATT-12)",
                ApiErrorCode.BOOKING_STATE_CHANGED);

        Booking walkIn = flows.walkIn();
        assertRefused(walkIn, AttendanceResult.ATTENDED, "manual", ApiErrorCode.BOOKING_STATE_CHANGED);
    }

    private void assertRefused(Booking booking, AttendanceResult outcome, String reason, ApiErrorCode code) {
        String result = attendanceResult(booking);
        assertThatThrownBy(() -> corrections.correct(admin, booking.bookingId(), outcome, reason))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
        assertThat(attendanceResult(booking)).isEqualTo(result);
        assertThat(chain(booking)).isEmpty();
        assertThat(adminEvidence(booking)).isZero();
        assertThat(correctedEvents(booking)).isEmpty();
    }

    /** Each AttendanceCorrected of the booking: "correction previous->result bookingStatus", in outbox order. */
    static List<String> correctedEvents(Booking booking) {
        return OWNER.sql("""
                        SELECT payload ->> 'correction', payload ->> 'previousResult', payload ->> 'attendanceResult',
                               payload ->> 'bookingStatus', payload ->> 'customerId', aggregate_type
                          FROM technical.outbox_event
                         WHERE aggregate_id = :id AND event_type = 'AttendanceCorrected' ORDER BY id""")
                .param("id", booking.bookingId())
                .query((rs, n) -> {
                    assertThat(rs.getString(5)).isEqualTo(booking.customerId().toString());
                    assertThat(rs.getString(6)).isEqualTo("BOOKING");
                    return rs.getString(1) + " " + rs.getString(2) + "->" + rs.getString(3) + " " + rs.getString(4);
                })
                .list();
    }

    // ----------------------------------------------------------------------------------------------------- support

    /** A booking played without evidence and completed by the real T5: COMPLETED + ATTENDANCE_UNCONFIRMED, 0 points. */
    private Booking completedUnconfirmed() {
        Booking booking = flows.pastItsEnd(flows.active(Duration.ofMinutes(5)), Duration.ofMinutes(10));
        assertThat(flows.fireCompletion(booking)).as("T5 settled").isTrue();
        assertThat(attendanceResult(booking)).isEqualTo("ATTENDANCE_UNCONFIRMED");
        return booking;
    }

    private static CommandScope scopeOf(Booking booking) {
        return CommandScope.shared(LockSet.builder().users(booking.customerId()).venues(booking.venueId())
                .resources(booking.resourceId()).build());
    }

    /** Each correction: its id, the correction it supersedes ("-" for the first) and its outcome, in sequence. */
    private static List<List<String>> chain(Booking booking) {
        return OWNER.sql("""
                        SELECT id, superseded_correction_event_id, corrected_outcome FROM trust.trust_event
                         WHERE booking_id = :id AND event_type = 'ATTENDANCE_CORRECTED' ORDER BY event_sequence""")
                .param("id", booking.bookingId())
                .query((rs, n) -> List.of(rs.getString(1), rs.getString(2) == null ? "-" : rs.getString(2),
                        rs.getString(3)))
                .list();
    }

    private static String attendanceResult(Booking booking) {
        return OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).optional().orElse(null);
    }

    private static String status(Booking booking) {
        return OWNER.sql("SELECT status FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query(String.class).single();
    }

    private static int points(UUID customer) {
        return OWNER.sql("SELECT balance FROM loyalty.points_account WHERE user_id = :id")
                .param("id", customer).query(Integer.class).single();
    }

    private static List<String> pointsMovements(Booking booking) {
        return OWNER.sql("""
                        SELECT cause || ' ' || amount FROM loyalty.points_transaction
                         WHERE booking_id = :id ORDER BY ledger_sequence""")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private static long rewardApplications(Booking booking) {
        return OWNER.sql("SELECT count(*) FROM loyalty.reward_application WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    /** Admin ATTENDANCE_CORRECTED history rows matched by an Admin audit row with a reason, at the same instant. */
    private static long adminEvidence(Booking booking) {
        return OWNER.sql("""
                        SELECT count(*) FROM booking.booking_history bh
                          JOIN audit.audit_event ae ON ae.subject_type = 'BOOKING' AND ae.subject_id = bh.booking_id
                           AND ae.action = 'ATTENDANCE_CORRECTED' AND ae.actor_type = 'ADMIN'
                           AND ae.actor_id = bh.actor_id AND ae.occurred_at = bh.occurred_at
                           AND btrim(ae.reason) <> ''
                         WHERE bh.booking_id = :id AND bh.entry_kind = 'BOOKING_EVENT'
                           AND bh.event_type = 'ATTENDANCE_CORRECTED' AND bh.actor_type = 'ADMIN'""")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    /** consecutive no-shows, completed attended count. */
    private static List<Integer> projection(UUID customer) {
        return OWNER.sql("""
                        SELECT consecutive_no_shows, completed_attended_count FROM trust.trust_projection
                         WHERE user_id = :id""")
                .param("id", customer).query((rs, n) -> List.of(rs.getInt(1), rs.getInt(2))).single();
    }

    private static long projectionVersion(UUID customer) {
        return OWNER.sql("SELECT projection_version FROM trust.trust_projection WHERE user_id = :id")
                .param("id", customer).query(Long.class).single();
    }

    /** Every table a reconciliation could write for the Customer. */
    private static List<Long> derivedCounts(UUID customer) {
        return OWNER.sql("""
                        SELECT (SELECT count(*) FROM trust.trust_event WHERE subject_user_id = :id),
                               (SELECT count(*) FROM trust.ban_qualification WHERE user_id = :id),
                               (SELECT count(*) FROM trust.ban_instance WHERE user_id = :id),
                               (SELECT count(*) FROM trust.verified_player_transition WHERE user_id = :id),
                               (SELECT count(*) FROM trust.cascade_execution WHERE subject_user_id = :id),
                               (SELECT count(*) FROM loyalty.points_transaction WHERE points_account_id = :id),
                               (SELECT count(*) FROM loyalty.reward_application WHERE user_id = :id),
                               (SELECT count(*) FROM audit.audit_event WHERE subject_type = 'BOOKING'
                                   AND subject_id IN (SELECT id FROM booking.booking WHERE customer_id = :id))""")
                .param("id", customer)
                .query((rs, n) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7), rs.getLong(8)))
                .single();
    }
}
