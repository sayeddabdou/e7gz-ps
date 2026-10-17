package ps.e7gz.attendance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-59 against the real database: each attendance action writes its evidence, history, audit and one integration event
 * in one transaction, which the database's own guards accept at commit; a no-show also moves ACTIVE -> NO_SHOW,
 * releases the reservation at the no-show instant (the room is sellable again at once) and writes the canonical
 * {@code BOOKING_NO_SHOW}; refusals and failures write nothing; concurrent actions write the evidence once. The exact
 * window boundaries are {@link CheckInBoundaryTest}'s.
 */
class AttendanceIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    ApplicationContext context;

    @Autowired
    Attendances attendances;

    BookingFlows flows;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        flows = BookingFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------------- check-in

    @Test
    void aCheckInRecordsTheEvidenceByTheVenueAccountWithItsHistoryAuditAndOneEvent() {
        Booking booking = flows.active(Duration.ofMinutes(5));

        RecordedAttendance recorded = record(AttendanceAction.CHECK_IN, booking);

        assertThat(recorded.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(recorded.attendanceResult()).isNull();
        assertThat(recorded.latePresentAt()).isNull();
        assertThat(recorded.noShowAt()).isNull();
        Instant at = recorded.checkedInAt();
        assertThat(at).isNotNull();
        assertThat(record(booking)).containsEntry("check_in_at", at)
                .containsEntry("recorded_by_venue_account_id", booking.venueAccountId())
                .containsEntry("result", null);
        assertThat(venueEvents(booking)).containsExactly("CHECK_IN_RECORDED " + at + " " + booking.venueAccountId());
        assertThat(audits(booking)).containsExactly("CHECK_IN_RECORDED VENUE " + booking.venueAccountId() + " " + at);
        assertThat(status(booking)).isEqualTo("ACTIVE");
        assertThat(activeReservations(booking)).isOne();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.CHECK_IN_RECORDED);
        assertThat(payload(booking, IntegrationEvents.CHECK_IN_RECORDED)).containsEntry("bookingId",
                        booking.bookingId().toString()).containsEntry("customerId", booking.customerId().toString())
                .containsEntry("venueId", booking.venueId().toString())
                .containsEntry("resourceId", booking.resourceId().toString())
                .containsEntry("startAt", booking.startAt().toString())
                .containsEntry("endAt", booking.startAt().plus(Duration.ofMinutes(60)).toString())
                .containsEntry("recordedAt", at.toString());
    }

    @Test
    void aSecondCheckInIsRefusedAndWritesNothing() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        record(AttendanceAction.CHECK_IN, booking);

        assertRefused(AttendanceAction.CHECK_IN, booking, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);

        assertThat(venueEvents(booking)).hasSize(1);
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.CHECK_IN_RECORDED);
    }

    /** FR-ATT-04: once checked in, neither Late Present nor no-show - outside their window here, refused either way. */
    @Test
    void aCheckedInBookingIsNeverMarkedLateOrNoShowInTheCheckInWindow() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        record(AttendanceAction.CHECK_IN, booking);

        assertRefused(AttendanceAction.LATE_PRESENT, booking, ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE);
        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);
        assertThat(status(booking)).isEqualTo("ACTIVE");
    }

    @Test
    void afterTheCheckInWindowCheckInIsRefusedAndLatePresentIsTheAction() {
        Booking booking = flows.active(Duration.ofMinutes(20));

        assertRefused(AttendanceAction.CHECK_IN, booking, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);
        RecordedAttendance late = record(AttendanceAction.LATE_PRESENT, booking);

        assertThat(late.status()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(late.latePresentAt()).isNotNull();
        assertThat(late.checkedInAt()).isNull();
        assertThat(venueEvents(booking)).containsExactly("LATE_PRESENT_RECORDED " + late.latePresentAt() + " "
                + booking.venueAccountId());
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.LATE_PRESENT_RECORDED);
    }

    /** FR-ATT-04: a customer marked Late Present can never afterwards be marked no-show for the same booking. */
    @Test
    void aLatePresentBookingCannotBeMarkedNoShowAndKeepsItsRoom() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        record(AttendanceAction.LATE_PRESENT, booking);

        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);
        assertRefused(AttendanceAction.LATE_PRESENT, booking, ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE);

        assertThat(status(booking)).isEqualTo("ACTIVE");
        assertThat(activeReservations(booking)).isOne();
        assertThat(trustEvents(booking)).isZero();
        assertThat(eventTypes(booking)).doesNotContain(IntegrationEvents.NO_SHOW_RECORDED);
    }

    @Test
    void beforeTheStartCheckInIsRefused() {
        Booking booking = flows.pending();
        flows.accept(booking);

        assertRefused(AttendanceAction.CHECK_IN, booking, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);
        assertThat(record(booking)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------- no-show

    @Test
    void aNoShowEndsTheBookingReleasesTheRoomAtItsInstantAndWritesTheTrustOutcome() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        Instant end = booking.startAt().plus(Duration.ofMinutes(60));

        RecordedAttendance recorded = record(AttendanceAction.NO_SHOW, booking);

        Instant at = recorded.noShowAt();
        assertThat(at).isNotNull();
        assertThat(recorded.status()).isEqualTo(BookingStatus.NO_SHOW);
        assertThat(recorded.attendanceResult()).isEqualTo(AttendanceResult.ABSENT_NO_SHOW);
        assertThat(record(booking)).containsEntry("no_show_at", at).containsEntry("result", "ABSENT_NO_SHOW")
                .containsEntry("resolved_at", at);
        assertThat(OWNER.sql("""
                        SELECT status, terminal_at, terminal_reason IS NULL, commitment_period IS NULL, end_at
                          FROM booking.booking WHERE id = :id""")
                .param("id", booking.bookingId())
                .query((rs, n) -> List.of(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getBoolean(3), rs.getBoolean(4), rs.getObject(5, OffsetDateTime.class).toInstant()))
                .single())
                .as("NO_SHOW at the no-show instant, no reason, no commitment, endAt untouched")
                .containsExactly("NO_SHOW", at, true, true, end);
        assertThat(OWNER.sql("""
                        SELECT active_flag, actual_reservation_release_at, planned_start, planned_end
                          FROM inventory.resource_reservation WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("id", booking.bookingId())
                .query((rs, n) -> List.of(rs.getBoolean(1),
                        rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getObject(3, OffsetDateTime.class).toInstant(),
                        rs.getObject(4, OffsetDateTime.class).toInstant())).single())
                .as("released at the no-show instant, its planned interval kept")
                .containsExactly(false, at, booking.startAt(), end);
        assertThat(OWNER.sql("""
                        SELECT from_status || '->' || to_status || ' ' || actor_type || ' ' || actor_id
                          FROM booking.booking_history WHERE booking_id = :id AND entry_kind = 'STATUS_TRANSITION'
                         ORDER BY sequence DESC LIMIT 1""")
                .param("id", booking.bookingId()).query(String.class).single())
                .isEqualTo("ACTIVE->NO_SHOW VENUE " + booking.venueAccountId());
        assertThat(venueEvents(booking)).containsExactly("NO_SHOW_RECORDED " + at + " " + booking.venueAccountId());
        assertThat(audits(booking)).containsExactly("NO_SHOW_RECORDED VENUE " + booking.venueAccountId() + " " + at);
        assertThat(trustEvents(booking)).isOne();
        assertThat(OWNER.sql("SELECT consecutive_no_shows FROM trust.trust_projection WHERE user_id = :id")
                .param("id", booking.customerId()).query(Integer.class).single()).isOne();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.NO_SHOW_RECORDED)
                .doesNotContain("BookingNoShowRecorded");
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event e
                          JOIN inventory.resource_reservation r ON r.id = e.aggregate_id
                         WHERE r.booking_id = :id AND e.event_type = :type""")
                .param("id", booking.bookingId()).param("type", IntegrationEvents.RESERVATION_RELEASED)
                .query(Long.class).single()).isOne();
    }

    /** FR-ATT-05: the remaining interval is sellable again at once, manually here, under the normal rules. */
    @Test
    void afterANoShowTheRoomCanBeSoldAgainAtOnce() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        assertThatThrownBy(() -> flows.walkInOnRoomOf(booking, 60)).as("the room is taken before the no-show")
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE));

        record(AttendanceAction.NO_SHOW, booking);

        UUID walkIn = flows.walkInOnRoomOf(booking, 60);
        assertThat(OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", walkIn)
                .query(String.class).single()).isEqualTo("ACTIVE");
    }

    /** FR-ATT-06: a committed no-show is terminal; venue staff cannot undo or overwrite it. */
    @Test
    void aCommittedNoShowCannotBeUndoneByTheVenue() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        record(AttendanceAction.NO_SHOW, booking);

        for (AttendanceAction action : AttendanceAction.values()) {
            assertRefused(action, booking, ApiErrorCode.BOOKING_STATE_CHANGED);
        }
        assertThat(status(booking)).isEqualTo("NO_SHOW");
        assertThat(trustEvents(booking)).isOne();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.NO_SHOW_RECORDED);
    }

    /**
     * The third-consecutive-no-show boundary (arch 07 sections 3.1, 4 and 5.1; FR-TRS-13, FR-TRS-14): at stage 0 the
     * third {@code BOOKING_NO_SHOW} brings the replayed counter to the threshold 3, so replay derives a Ban
     * Qualification. {@code trust.assert_user_integrity} (via {@code tr_trust_event_projection_current}) then requires
     * its exactly-once operational consequence - a Ban Instance consuming it, with its cancellation cascade - in the
     * same transaction. That is the O14 reconciler of E7-71, not built yet, so today the database refuses the commit
     * and the whole no-show rolls back: nothing partial is left. E7-71 replaces this test's expectation with the ban.
     */
    @Test
    void theThirdConsecutiveNoShowNeedsE771sBanAndUntilThenRollsBackWhole() {
        BookingFlows.Customer customer = flows.customer();
        for (int i = 0; i < 2; i++) {
            Booking earlier = customer.nextActive(Duration.ofMinutes(20));
            assertThat(record(AttendanceAction.NO_SHOW, earlier).status()).isEqualTo(BookingStatus.NO_SHOW);
        }
        Booking third = customer.nextActive(Duration.ofMinutes(20));
        assertThat(consecutiveNoShows(third)).isEqualTo(2);

        assertThatThrownBy(() -> record(AttendanceAction.NO_SHOW, third))
                .rootCause().hasMessageContaining("has no exactly-once operational consequence");

        assertThat(status(third)).isEqualTo("ACTIVE");
        assertThat(activeReservations(third)).isOne();
        assertThat(record(third)).isEmpty();
        assertThat(venueEvents(third)).isEmpty();
        assertThat(audits(third)).isEmpty();
        assertThat(trustEvents(third)).isZero();
        assertThat(eventTypes(third)).doesNotContain(IntegrationEvents.NO_SHOW_RECORDED);
        assertThat(consecutiveNoShows(third)).as("the projection is unchanged").isEqualTo(2);
        assertThat(OWNER.sql("SELECT count(*) FROM trust.ban_instance WHERE user_id = :id")
                .param("id", third.customerId()).query(Long.class).single()).isZero();
    }

    // ----------------------------------------------------------------------------------- refusals by booking

    /** FR-MAN-04: a Manual Booking has no attendance actions; each answers its own code and writes nothing. */
    @Test
    void aManualBookingAnswersEachActionsOwnCode() {
        Booking walkIn = flows.walkIn();

        assertRefused(AttendanceAction.CHECK_IN, walkIn, ApiErrorCode.CHECK_IN_WINDOW_CLOSED);
        assertRefused(AttendanceAction.LATE_PRESENT, walkIn, ApiErrorCode.LATE_PRESENT_NOT_AVAILABLE);
        assertRefused(AttendanceAction.NO_SHOW, walkIn, ApiErrorCode.NO_SHOW_NOT_AVAILABLE);
        assertThat(record(walkIn)).isEmpty();
        assertThat(status(walkIn)).isEqualTo("ACTIVE");
    }

    @Test
    void aBookingNoLongerInProgressIsBookingStateChanged() {
        Booking booking = flows.pending();
        flows.customerCancels(booking);

        assertRefused(AttendanceAction.CHECK_IN, booking, ApiErrorCode.BOOKING_STATE_CHANGED);
        assertRefused(AttendanceAction.NO_SHOW, booking, ApiErrorCode.BOOKING_STATE_CHANGED);
    }

    @Test
    void anotherVenuesBookingIsNotFound() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        UUID otherAccount = Fixtures.aVenueAccount().create().id();

        assertThatThrownBy(() -> attendances.record(AttendanceAction.CHECK_IN, otherAccount, booking.bookingId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThat(record(booking)).isEmpty();
    }

    // ------------------------------------------------------------------------------------ atomicity, races

    /**
     * D11 (E7-47): a no-show on an ACTIVE booking without its reservation fails after the evidence and the NO_SHOW
     * transition were written - the whole command rolls back: no evidence, no transition, no Trust outcome, no event.
     */
    @Test
    void aFailingNoShowRollsBackEverythingItWrote() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        withoutTriggers("""
                UPDATE inventory.resource_reservation
                   SET active_flag = false, actual_reservation_release_at = actual_reservation_start,
                       release_reason = 'E7-59 test defect'
                 WHERE booking_id = :id AND active_flag""", booking.bookingId());
        long outboxBefore = eventTypes(booking).size();

        assertThatThrownBy(() -> record(AttendanceAction.NO_SHOW, booking))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity failure");

        assertThat(status(booking)).isEqualTo("ACTIVE");
        assertThat(record(booking)).isEmpty();
        assertThat(venueEvents(booking)).isEmpty();
        assertThat(audits(booking)).isEmpty();
        assertThat(trustEvents(booking)).isZero();
        assertThat(eventTypes(booking)).hasSize((int) outboxBefore);
    }

    @Test
    void concurrentNoShowsWriteTheEvidenceTheReleaseAndTheEventsOnce() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(20));

        ConcurrentRace.Result<RecordedAttendance> race = race(booking, racer -> AttendanceAction.NO_SHOW);

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        assertThat(venueEvents(booking)).hasSize(1);
        assertThat(trustEvents(booking)).isOne();
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.NO_SHOW_RECORDED);
    }

    @Test
    void concurrentCheckInsWriteTheEvidenceOnce() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(5));

        ConcurrentRace.Result<RecordedAttendance> race = race(booking, racer -> AttendanceAction.CHECK_IN);

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.CHECK_IN_WINDOW_CLOSED));
        assertThat(venueEvents(booking)).hasSize(1);
        assertThat(eventTypes(booking)).containsOnlyOnce(IntegrationEvents.CHECK_IN_RECORDED);
    }

    /** Late Present against no-show: exactly one wins, and the evidence stays mutually exclusive (FR-ATT-04). */
    @Test
    void latePresentRacingANoShowLeavesExactlyOneOfThem() throws Exception {
        Booking booking = flows.active(Duration.ofMinutes(20));

        ConcurrentRace.Result<RecordedAttendance> race = race(booking, racer ->
                racer == 0 ? AttendanceAction.LATE_PRESENT : AttendanceAction.NO_SHOW);

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOf(ApiException.class);
        Map<String, Object> evidence = record(booking);
        assertThat(evidence.get("late_present_at") == null).isNotEqualTo(evidence.get("no_show_at") == null);
        assertThat(venueEvents(booking)).hasSize(1);
        assertThat(status(booking)).isEqualTo(evidence.get("no_show_at") == null ? "ACTIVE" : "NO_SHOW");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ConcurrentRace.Result<RecordedAttendance> race(Booking booking,
                                                           IntFunction<AttendanceAction> actionOf)
            throws Exception {
        return ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            AttendanceAction action = actionOf.apply(racer);
            start.await();
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                return attendances.record(action, booking.venueAccountId(), booking.bookingId());
            }
        });
    }

    private RecordedAttendance record(AttendanceAction action, Booking booking) {
        return attendances.record(action, booking.venueAccountId(), booking.bookingId());
    }

    private void assertRefused(AttendanceAction action, Booking booking, ApiErrorCode code) {
        assertThatThrownBy(() -> record(action, booking)).as(action.name())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).as(action.name())
                        .isEqualTo(code));
    }

    /** The booking's attendance record as column -> value (instants as {@link Instant}); empty when none. */
    static Map<String, Object> record(Booking booking) {
        return OWNER.sql("""
                        SELECT result, check_in_at, late_present_at, no_show_at, resolved_at,
                               recorded_by_venue_account_id
                          FROM attendance.attendance_record WHERE booking_id = :id""")
                .param("id", booking.bookingId())
                .query((rs, n) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("result", rs.getString("result"));
                    for (String column : List.of("check_in_at", "late_present_at", "no_show_at", "resolved_at")) {
                        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
                        row.put(column, value == null ? null : value.toInstant());
                    }
                    row.put("recorded_by_venue_account_id", rs.getObject("recorded_by_venue_account_id", UUID.class));
                    return row;
                })
                .optional().orElse(Map.of());
    }

    static String status(Booking booking) {
        return OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", booking.bookingId())
                .query(String.class).single();
    }

    /** The attendance Booking Events, "EVENT at actorId", oldest first. */
    static List<String> venueEvents(Booking booking) {
        return OWNER.sql("""
                        SELECT event_type, occurred_at, actor_type, actor_id FROM booking.booking_history
                         WHERE booking_id = :id AND entry_kind = 'BOOKING_EVENT' ORDER BY sequence""")
                .param("id", booking.bookingId())
                .query((rs, n) -> {
                    assertThat(rs.getString("actor_type")).isEqualTo("VENUE");
                    return rs.getString("event_type") + " " + rs.getObject("occurred_at", OffsetDateTime.class)
                            .toInstant() + " " + rs.getObject("actor_id", UUID.class);
                })
                .list();
    }

    /** "ACTION ACTOR_TYPE actorId at" of the booking's audit rows. */
    static List<String> audits(Booking booking) {
        return OWNER.sql("""
                        SELECT action, actor_type, actor_id, occurred_at FROM audit.audit_event
                         WHERE subject_type = 'BOOKING' AND subject_id = :id ORDER BY occurred_at""")
                .param("id", booking.bookingId())
                .query((rs, n) -> rs.getString("action") + " " + rs.getString("actor_type") + " "
                        + rs.getObject("actor_id", UUID.class) + " "
                        + rs.getObject("occurred_at", OffsetDateTime.class).toInstant())
                .list();
    }

    static long trustEvents(Booking booking) {
        return OWNER.sql("SELECT count(*) FROM trust.trust_event WHERE booking_id = :id AND event_type = 'BOOKING_NO_SHOW'")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    static int consecutiveNoShows(Booking booking) {
        return OWNER.sql("SELECT consecutive_no_shows FROM trust.trust_projection WHERE user_id = :id")
                .param("id", booking.customerId()).query(Integer.class).single();
    }

    static long activeReservations(Booking booking) {
        return OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    static List<String> eventTypes(Booking booking) {
        return OWNER.sql("SELECT event_type FROM technical.outbox_event WHERE aggregate_id = :id ORDER BY id")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> payload(Booking booking, String type) {
        String json = OWNER.sql("""
                        SELECT payload::text FROM technical.outbox_event WHERE aggregate_id = :id AND event_type = :type""")
                .param("id", booking.bookingId()).param("type", type).query(String.class).single();
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fixture write past every trigger, as the booking tests plant defects ({@code VenueAnswerDb}). */
    private static void withoutTriggers(String sql, UUID id) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            jdbc.sql(sql).param("id", id).update();
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }
}
