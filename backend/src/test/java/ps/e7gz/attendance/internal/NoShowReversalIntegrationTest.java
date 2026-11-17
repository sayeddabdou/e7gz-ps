package ps.e7gz.attendance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.TrustEventType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.trust.api.TrustReplay;

/**
 * E7-70 no-show reversal on the real database (FR-ATT-11..13, FR-PTS-15, Product v5.0 sections 27.8-27.9, arch 07
 * section 7.4): every booking, attendance, history, audit, Trust and Loyalty write of the Admin command commits together
 * and the database's guards accept it; refusals and failures write nothing. No-shows are recorded through the real venue
 * command; a booking past its original endAt is one whose real endAt the database clock has passed (a booking lasts an
 * hour and the clock cannot be moved). The exact endAt instant is decided by the booking module before the database's
 * own refusal ({@code tr_03_booking_transition_guard}) and is unit-tested to the microsecond in
 * {@code NoShowReversalTargetTest}.
 *
 * <p>Pending on E7-71 (not bypassed): a reversal that leaves an operational Ban without replay support is handed to the
 * Ban reconciliation and today rolls back ({@code TrustConsequenceUnavailableException}). It cannot be staged yet: no Ban
 * can exist until E7-71 creates one, because the third no-show itself still rolls back.
 */
class NoShowReversalIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private static final String ENDPOINT = "POST /api/v1/admin/bookings/{bookingId}/attendance/reverse-no-show";

    @Autowired
    ApplicationContext context;

    @Autowired
    AttendanceCorrections corrections;

    @Autowired
    TrustReplay replay;

    @Autowired
    Idempotency idempotency;

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

    // ------------------------------------------------------------------------------------------- before the endAt

    /**
     * Penalty removal only, before the original endAt: CANCELLED / ADMIN_NO_SHOW_REVERSAL, ATTENDANCE_UNCONFIRMED (the
     * further correction of section 27.8), no penalty, no reward, no refund without service loss, and the released room
     * stays released. The no-show evidence is kept, only marked reversed.
     */
    @Test
    void penaltyRemovalBeforeTheEndCancelsWithoutRewardOrRefund() {
        Booking missed = missed(flows.active(Duration.ofMinutes(20)));
        assertThat(counter(missed.customerId())).isOne();

        ReversedNoShow reversed = corrections.reverseNoShow(admin, missed.bookingId(),
                AttendanceResult.ATTENDANCE_UNCONFIRMED, false, "venue marked the wrong booking");

        assertThat(reversed.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking(missed)).containsExactly("CANCELLED", "ADMIN_NO_SHOW_REVERSAL");
        assertThat(attendanceRow(missed)).containsExactly("ATTENDANCE_UNCONFIRMED", "no-show kept", "reversed");
        assertThat(trustEvents(missed)).containsExactly("BOOKING_NO_SHOW", "NO_SHOW_REVERSED", "ATTENDANCE_CORRECTED");
        assertThat(effects(missed)).containsExactly(TrustEventType.BOOKING_ATTENDANCE_UNCONFIRMED);
        assertThat(counter(missed.customerId())).isZero();
        assertThat(points(missed.customerId())).isZero();
        assertThat(feeDecision(missed)).containsExactly("BOOKING_SERVICE_LOSS_NOT_ESTABLISHED", "0.00");
        assertThat(activeReservations(missed)).as("never re-acquired").isZero();
        assertThat(adminEvidence(missed, "NO_SHOW_REVERSED")).isOne();
        assertThat(adminEvidence(missed, "ATTENDANCE_CORRECTED")).isOne();
        assertThat(events(missed)).as("one event for reversal + correction")
                .containsExactly("NO_SHOW_REVERSED ABSENT_NO_SHOW->ATTENDANCE_UNCONFIRMED CANCELLED");
    }

    /**
     * The freed slot was sold to another customer (FR-ATT-05): the reversal never displaces them. Attended with the room
     * reallocated: ATTENDED_INTERRUPTED resets the counter with no points; confirmed service loss is a FULL decision.
     */
    @Test
    void aReversalNeverDisplacesTheCustomerWhoTookTheFreedSlot() {
        Booking missed = missed(flows.active(Duration.ofMinutes(20)));
        UUID walkIn = flows.walkInOnRoomOf(missed, 60);                      // overlaps the freed remainder

        ReversedNoShow reversed = corrections.reverseNoShow(admin, missed.bookingId(),
                AttendanceResult.ATTENDED_INTERRUPTED, true, "customer was there, room given away");

        assertThat(reversed.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", walkIn)
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = :id AND active_flag""")
                .param("id", walkIn).query(Long.class).single()).isOne();
        assertThat(activeReservations(missed)).isZero();
        assertThat(attendanceRow(missed).getFirst()).isEqualTo("ATTENDED_INTERRUPTED");
        assertThat(effects(missed)).containsExactly(TrustEventType.BOOKING_ATTENDED_INTERRUPTED);
        assertThat(counter(missed.customerId())).isZero();
        assertThat(points(missed.customerId())).isZero();
        assertThat(feeDecision(missed).getFirst()).isEqualTo("BOOKING_SERVICE_LOSS_CONFIRMED");
        assertThat(feeDecision(missed).getLast()).isEqualTo(feeSnapshot(missed));
    }

    /** Attended and actually played (section 27.9): ATTENDED with its +200 and +1 progress, at the original value. */
    @Test
    void attendedBeforeTheEndGrantsTheRewardThroughReconciliation() {
        Booking missed = missed(flows.active(Duration.ofMinutes(20)));

        corrections.reverseNoShow(admin, missed.bookingId(), AttendanceResult.ATTENDED, false, "was playing");

        assertThat(booking(missed)).containsExactly("CANCELLED", "ADMIN_NO_SHOW_REVERSAL");
        assertThat(effects(missed)).containsExactly(TrustEventType.BOOKING_ATTENDED);
        assertThat(replay.replay(missed.customerId(), null).completedAttendedCount()).isOne();
        assertThat(points(missed.customerId())).isEqualTo(200);
    }

    // -------------------------------------------------------------------------------------- at and after the endAt

    /**
     * After the original endAt: COMPLETED / ATTENDANCE_UNCONFIRMED by default - only NO_SHOW_REVERSED, no further
     * correction, resolved at the reversal instant.
     */
    @Test
    void afterTheEndTheDefaultIsCompletedUnconfirmed() {
        Booking missed = missedThenEnded();

        ReversedNoShow reversed = corrections.reverseNoShow(admin, missed.bookingId(),
                AttendanceResult.ATTENDANCE_UNCONFIRMED, false, "penalty invalid");

        assertThat(reversed.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(reversed.correctionEventId()).isNull();
        assertThat(booking(missed)).containsExactly("COMPLETED", "-");
        assertThat(attendanceRow(missed)).containsExactly("ATTENDANCE_UNCONFIRMED", "no-show kept", "reversed");
        assertThat(trustEvents(missed)).containsExactly("BOOKING_NO_SHOW", "NO_SHOW_REVERSED");
        assertThat(replay.resolveEffectiveOutcomes(missed.customerId(), null)).singleElement().satisfies(effect -> {
            assertThat(effect.eventId()).isEqualTo(reversed.reversalEventId());
            assertThat(effect.type()).isEqualTo(TrustEventType.BOOKING_ATTENDANCE_UNCONFIRMED);
            assertThat(effect.effectiveAt()).isEqualTo(missed.startAt());
        });
        assertThat(counter(missed.customerId())).isZero();
        assertThat(feeDecision(missed)).containsExactly("BOOKING_SERVICE_LOSS_NOT_ESTABLISHED", "0.00");
        assertThat(events(missed))
                .containsExactly("NO_SHOW_REVERSED ABSENT_NO_SHOW->ATTENDANCE_UNCONFIRMED COMPLETED");
    }

    /** After the endAt with attendance separately established: COMPLETED + ATTENDED, +200 once. */
    @Test
    void afterTheEndAnEstablishedAttendanceCompletesAttended() {
        Booking missed = missedThenEnded();

        ReversedNoShow reversed = corrections.reverseNoShow(admin, missed.bookingId(), AttendanceResult.ATTENDED,
                false, "CCTV shows the full session");

        assertThat(reversed.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(reversed.correctionEventId()).isNotNull();
        assertThat(attendanceRow(missed).getFirst()).isEqualTo("ATTENDED");
        assertThat(effects(missed)).containsExactly(TrustEventType.BOOKING_ATTENDED);
        assertThat(points(missed.customerId())).isEqualTo(200);
        assertThat(pointsMovements(missed)).containsExactly("ATTENDED_COMPLETION 200");
        assertThat(events(missed)).containsExactly("NO_SHOW_REVERSED ABSENT_NO_SHOW->ATTENDED COMPLETED");
    }

    // ------------------------------------------------------------------------------------- Trust replay, penalties

    /**
     * Reversing the earlier of two no-shows later changes the earlier position (FR-TRS-05): the counter drops to 1, the
     * timeline keeps both bookings at their own startAt, and the later no-show is untouched.
     */
    @Test
    void reversingAnEarlierNoShowRemovesItsPenaltyAtItsOwnPosition() {
        BookingFlows.Customer customer = flows.customer();
        Booking first = missed(customer.nextActive(Duration.ofMinutes(45)));
        Booking second = missed(customer.nextActive(Duration.ofMinutes(20)));
        assertThat(counter(first.customerId())).isEqualTo(2);

        corrections.reverseNoShow(admin, first.bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, false, "wrong");

        List<TrustReplay.Step> timeline = replay.timeline(first.customerId(), null);
        assertThat(timeline).extracting(TrustReplay.Step::effectiveAt).containsExactly(first.startAt(), second.startAt());
        assertThat(timeline).extracting(TrustReplay.Step::consecutiveNoShows).containsExactly(0, 1);
        assertThat(counter(first.customerId())).isOne();
        assertThat(booking(second)).containsExactly("NO_SHOW", "-");
    }

    /**
     * E7-69 carry-forward (PR #102 review, 15 Nov 2026; arch 07 sections 2.3 and 9 "Reordering correction",
     * FR-TRS-05): a no-show on Monday, reversed on Friday through the real command and committed, changes the Monday
     * position of {@link TrustReplay} and adds nothing at Friday's own time. Real days apart: the Monday and Wednesday
     * no-shows are recorded by the real venue command and then moved back whole, four and two days; "Friday" is now.
     */
    @Test
    void aMondayNoShowReversedOnFridayChangesTheMondayPositionAndAddsNothingOnFriday() {
        BookingFlows.Customer customer = flows.customer();
        Booking monday = flows.noShowLongAgo(missed(customer.nextActive(Duration.ofMinutes(20))), Duration.ofDays(4));
        Booking wednesday = flows.noShowLongAgo(missed(customer.nextActive(Duration.ofMinutes(20))),
                Duration.ofDays(2));
        UUID user = monday.customerId();
        Instant tuesday = monday.startAt().plus(Duration.ofDays(1));
        Instant thursday = wednesday.startAt().plus(Duration.ofDays(1));
        assertThat(replay.timeline(user, null)).extracting(TrustReplay.Step::consecutiveNoShows).containsExactly(1, 2);
        assertThat(replay.replay(user, TrustReplay.Boundary.at(tuesday)).consecutiveNoShows())
                .as("as of Tuesday, before the reversal").isOne();
        Instant friday = dbNow();

        corrections.reverseNoShow(admin, monday.bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, false,
                "The customer was there on Monday.");

        // committed: the reversal is an event of Friday that takes effect on Monday
        assertThat(OWNER.sql("""
                        SELECT (occurred_at >= :friday) || ' ' || (effective_at = :monday) FROM trust.trust_event
                         WHERE booking_id = :id AND event_type = 'NO_SHOW_REVERSED'""")
                .param("friday", OffsetDateTime.ofInstant(friday, java.time.ZoneOffset.UTC))
                .param("monday", OffsetDateTime.ofInstant(monday.startAt(), java.time.ZoneOffset.UTC))
                .param("id", monday.bookingId()).query(String.class).single())
                .as("occurred on Friday, effective on Monday").isEqualTo("true true");
        assertThat(booking(monday)).as("reversed after its endAt (FR-ATT-12)").containsExactly("COMPLETED", "-");
        // the Monday position changed ...
        List<TrustReplay.Effect> effects = replay.resolveEffectiveOutcomes(user, null);
        assertThat(effects).extracting(TrustReplay.Effect::effectiveAt)
                .containsExactly(monday.startAt(), wednesday.startAt());
        assertThat(effects).extracting(TrustReplay.Effect::type)
                .containsExactly(TrustEventType.BOOKING_ATTENDANCE_UNCONFIRMED, TrustEventType.BOOKING_NO_SHOW);
        assertThat(replay.timeline(user, null)).extracting(TrustReplay.Step::consecutiveNoShows).containsExactly(0, 1);
        assertThat(replay.replay(user, TrustReplay.Boundary.at(tuesday)).consecutiveNoShows())
                .as("as of Tuesday, after the reversal: history itself changed").isZero();
        // ... and nothing was added at Friday's own time
        assertThat(replay.replay(user, TrustReplay.Boundary.at(thursday))).as("nothing takes effect after Wednesday")
                .isEqualTo(replay.replay(user, null));
        assertThat(replay.timeline(user, null)).extracting(TrustReplay.Step::effectiveAt)
                .allSatisfy(at -> assertThat(at).isBefore(thursday));
        assertThat(counter(user)).as("the stored projection").isOne();
    }

    // ---------------------------------------------------------------------------- authorization and refusals

    /** FR-ATT-11: only an active Platform Admin; an unknown or deactivated one is refused and nothing is written. */
    @Test
    void onlyAnActiveAdminCanReverse() {
        Booking missed = missed(flows.active(Duration.ofMinutes(20)));
        UUID inactive = anAdmin().inactive().create().id();

        for (UUID who : List.of(UUID.randomUUID(), inactive)) {
            assertThatThrownBy(() -> corrections.reverseNoShow(who, missed.bookingId(),
                    AttendanceResult.ATTENDANCE_UNCONFIRMED, false, "not allowed")).isNotInstanceOf(ApiException.class);
            assertUnchanged(missed);
        }
    }

    @Test
    void refusalsWriteNothing() {
        Booking missed = missed(flows.active(Duration.ofMinutes(20)));
        assertRefused(missed, AttendanceResult.ATTENDANCE_UNCONFIRMED, false, " ", ApiErrorCode.VALIDATION_FAILED);
        assertRefused(missed, AttendanceResult.ATTENDANCE_UNCONFIRMED, null, "no assessment",
                ApiErrorCode.VALIDATION_FAILED);
        assertRefused(missed, AttendanceResult.ABSENT_NO_SHOW, false, "not a choice", ApiErrorCode.VALIDATION_FAILED);
        assertRefused(missed, null, false, "no choice", ApiErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> corrections.reverseNoShow(admin, UUID.randomUUID(), AttendanceResult.ATTENDED, false,
                "r")).isInstanceOf(ApiException.class).extracting("code").isEqualTo(ApiErrorCode.NOT_FOUND);

        Booking active = flows.active(Duration.ofMinutes(5));
        assertRefused(active, AttendanceResult.ATTENDED, false, "no no-show", ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        Booking walkIn = flows.walkIn();
        assertRefused(walkIn, AttendanceResult.ATTENDED, false, "manual", ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
    }

    /** A second reversal is refused: the booking is no longer NO_SHOW, and NO_SHOW_REVERSED exists once. */
    @Test
    void aDuplicateReversalIsRefused() {
        Booking missed = missed(flows.active(Duration.ofMinutes(20)));
        corrections.reverseNoShow(admin, missed.bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, false, "once");
        List<String> events = trustEvents(missed);

        assertThatThrownBy(() -> corrections.reverseNoShow(admin, missed.bookingId(), AttendanceResult.ATTENDED, true,
                "twice")).isInstanceOf(ApiException.class).extracting("code")
                .isEqualTo(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        assertThat(trustEvents(missed)).isEqualTo(events);
        assertThat(adminEvidence(missed, "NO_SHOW_REVERSED")).isOne();
        assertThat(events(missed)).hasSize(1);
    }

    // ------------------------------------------------------------------ idempotency, concurrency and atomicity

    /** ADR-015: a retry with the same Idempotency-Key returns the stored answer and writes nothing again. */
    @Test
    void aRetryWithTheSameKeyReplaysTheFirstAnswer() {
        Booking missed = missed(flows.active(Duration.ofMinutes(20)));
        IdempotentRequest key = key(missed, UUID.randomUUID(), AttendanceResult.ATTENDANCE_UNCONFIRMED);

        IdempotentResult<ReversedNoShow> first = reverse(missed, key);
        IdempotentResult<ReversedNoShow> again = reverse(missed, key);

        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(trustEvents(missed)).containsExactly("BOOKING_NO_SHOW", "NO_SHOW_REVERSED", "ATTENDANCE_CORRECTED");
        assertThat(adminEvidence(missed, "NO_SHOW_REVERSED")).isOne();
        assertThat(events(missed)).as("the retry publishes nothing")
                .containsExactly("NO_SHOW_REVERSED ABSENT_NO_SHOW->ATTENDANCE_UNCONFIRMED CANCELLED");
    }

    /** Two Admins at once: the Customer lock serialises them, one reverses, the other is ADMIN_ACTION_NOT_ALLOWED. */
    @Test
    void concurrentReversalsReverseOnce() {
        Booking missed = missed(flows.active(Duration.ofMinutes(20)));
        UUID otherAdmin = anAdmin().create().id();

        ConcurrentRace.Result<ReversedNoShow> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                start.await();
                return corrections.reverseNoShow(racer == 0 ? admin : otherAdmin, missed.bookingId(),
                        AttendanceResult.ATTENDANCE_UNCONFIRMED, false, "racer " + racer);
            }
        });

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
        assertThat(trustEvents(missed)).containsOnlyOnce("NO_SHOW_REVERSED");
        assertThat(adminEvidence(missed, "NO_SHOW_REVERSED")).isOne();
        assertThat(feeDecisions(missed)).isOne();
        assertThat(events(missed)).as("one event, by the winner only").hasSize(1);
    }

    /**
     * Atomicity: a failure after every write of the reversal (here in the answer, inside the transaction) rolls all of
     * it back - booking, attendance, history, audit, Trust, Loyalty and the Idempotency-Key record.
     */
    @Test
    void aFailureLateInTheCommandRollsEverythingBack() {
        Booking missed = missed(flows.active(Duration.ofMinutes(20)));
        UUID requestKey = UUID.randomUUID();

        assertThatThrownBy(() -> corrections.reverseNoShow(admin, missed.bookingId(), AttendanceResult.ATTENDED, true,
                "reason", key(missed, requestKey, AttendanceResult.ATTENDED),
                StoredResponse.of(ReversedNoShow.class, 200), reversed -> {
                    throw new IllegalStateException("injected after the writes");
                })).hasMessage("injected after the writes");

        assertUnchanged(missed);
        assertThat(OWNER.sql("SELECT count(*) FROM technical.idempotency_record WHERE idempotency_key = :key")
                .param("key", requestKey.toString()).query(Long.class).single()).isZero();
    }

    // ----------------------------------------------------------------------------------------------------- support

    private Booking missed(Booking active) {
        attendance.noShow(active.venueAccountId(), active.bookingId());
        return active;
    }

    /**
     * A no-show on a booking that ends a few seconds after it, then the wait (bounded, on the database clock) until its
     * real endAt has passed.
     */
    private Booking missedThenEnded() {
        Booking missed = missed(flows.active(Duration.ofMinutes(60).minusSeconds(6)));
        Instant end = missed.startAt().plus(Duration.ofMinutes(60));
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (dbNow().isBefore(end)) {
            assertThat(System.nanoTime()).as("the endAt passes").isLessThan(deadline);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return missed;
    }

    private IdempotentResult<ReversedNoShow> reverse(Booking missed, IdempotentRequest key) {
        return corrections.reverseNoShow(admin, missed.bookingId(), AttendanceResult.ATTENDANCE_UNCONFIRMED, false,
                "retry", key, StoredResponse.of(ReversedNoShow.class, 200,
                        reversed -> new ResultAggregate("BOOKING", reversed.bookingId())),
                reversed -> reversed);
    }

    private IdempotentRequest key(Booking booking, UUID key, AttendanceResult outcome) {
        return idempotency.request(new AuthenticatedSubject(AuthSubjectType.ADMIN, admin), ENDPOINT, key,
                Map.of("bookingId", booking.bookingId()), Map.of("outcome", outcome.name()));
    }

    private void assertRefused(Booking booking, AttendanceResult outcome, Boolean serviceLoss, String reason,
                               ApiErrorCode code) {
        List<String> before = booking(booking);
        assertThatThrownBy(() -> corrections.reverseNoShow(admin, booking.bookingId(), outcome, serviceLoss, reason))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
        assertThat(booking(booking)).isEqualTo(before);
        assertThat(adminEvidence(booking, "NO_SHOW_REVERSED")).isZero();
        assertThat(events(booking)).isEmpty();
    }

    private void assertUnchanged(Booking missed) {
        assertThat(booking(missed)).containsExactly("NO_SHOW", "-");
        assertThat(attendanceRow(missed)).containsExactly("ABSENT_NO_SHOW", "no-show kept", "not reversed");
        assertThat(trustEvents(missed)).containsExactly("BOOKING_NO_SHOW");
        assertThat(adminEvidence(missed, "NO_SHOW_REVERSED")).isZero();
        assertThat(feeDecisions(missed)).isZero();
        assertThat(events(missed)).isEmpty();
        assertThat(points(missed.customerId())).isZero();
        assertThat(counter(missed.customerId())).isOne();
    }

    /** The booking's AttendanceCorrected events (E7-70), as {@link AttendanceCorrectionIntegrationTest} reads them. */
    private static List<String> events(Booking booking) {
        return AttendanceCorrectionIntegrationTest.correctedEvents(booking);
    }

    private static Instant dbNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** status, terminal reason ("-" for none). */
    private static List<String> booking(Booking booking) {
        return OWNER.sql("SELECT status, COALESCE(terminal_reason, '-') FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query((rs, n) -> List.of(rs.getString(1), rs.getString(2)))
                .single();
    }

    /** result, whether the no-show is still recorded, whether it is reversed. */
    private static List<String> attendanceRow(Booking booking) {
        return OWNER.sql("""
                        SELECT result, no_show_at IS NOT NULL, no_show_reversed_at IS NOT NULL
                          FROM attendance.attendance_record WHERE booking_id = :id""")
                .param("id", booking.bookingId())
                .query((rs, n) -> List.of(rs.getString(1), rs.getBoolean(2) ? "no-show kept" : "no-show gone",
                        rs.getBoolean(3) ? "reversed" : "not reversed"))
                .single();
    }

    private static List<String> trustEvents(Booking booking) {
        return OWNER.sql("SELECT event_type FROM trust.trust_event WHERE booking_id = :id ORDER BY event_sequence")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private List<TrustEventType> effects(Booking booking) {
        return replay.resolveEffectiveOutcomes(booking.customerId(), null).stream()
                .filter(effect -> effect.effectiveAt().equals(booking.startAt()))
                .map(TrustReplay.Effect::type).toList();
    }

    private static int counter(UUID customer) {
        return OWNER.sql("SELECT consecutive_no_shows FROM trust.trust_projection WHERE user_id = :id")
                .param("id", customer).query(Integer.class).single();
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

    /** The assessment the decision rests on and the decided amount. */
    private static List<String> feeDecision(Booking booking) {
        return OWNER.sql("""
                        SELECT a.action, f.amount::text FROM loyalty.booking_fee_refund f
                          JOIN audit.audit_event a ON a.id = f.service_loss_audit_id
                         WHERE f.booking_id = :id""")
                .param("id", booking.bookingId()).query((rs, n) -> List.of(rs.getString(1), rs.getString(2)))
                .single();
    }

    private static long feeDecisions(Booking booking) {
        return OWNER.sql("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    private static String feeSnapshot(Booking booking) {
        return OWNER.sql("SELECT booking_fee_snapshot::text FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query(String.class).single();
    }

    private static long activeReservations(Booking booking) {
        return OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = :id AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    /** Admin {@code action} Booking Events matched by an Admin audit row with a reason at the same instant. */
    private static long adminEvidence(Booking booking, String action) {
        return OWNER.sql("""
                        SELECT count(*) FROM booking.booking_history bh
                          JOIN audit.audit_event ae ON ae.subject_type = 'BOOKING' AND ae.subject_id = bh.booking_id
                           AND ae.action = :action AND ae.actor_type = 'ADMIN'
                           AND ae.actor_id = bh.actor_id AND ae.occurred_at = bh.occurred_at
                           AND btrim(ae.reason) <> ''
                         WHERE bh.booking_id = :id AND bh.entry_kind = 'BOOKING_EVENT'
                           AND bh.event_type = :action AND bh.actor_type = 'ADMIN'""")
                .param("id", booking.bookingId()).param("action", action).query(Long.class).single();
    }
}
