package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.OnlineBookingWorld.CAIRO;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.VenueAnswerDb.HistoryRow;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-48 against the real database, every trigger on: creation (FR-MAN-01…FR-MAN-10), the business date and session
 * snapshot (FR-MAN-09; PO decision 10 Oct 2026, E7-48, D1-D3), the error mapping (D4), the venue's cancellation (PCR-007,
 * D5) with its commit-time provenance check, the jobs (D8) and events (D6). Each test builds its own world around the
 * database clock ({@link OnlineBookingWorld}: a PS5 room priced 77.77 EGP an hour and, unless the test replaces it, a
 * session {@code [t0-2h, t0+10h)}).
 */
class ManualBookingIntegrationTest extends IntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    ManualBookings manual;

    @Autowired
    OnlineBookingCreation online;

    @Autowired
    Idempotency idempotency;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    record ManualRow(String status, String source, UUID customerId, Instant startAt, Instant endAt, Instant createdAt,
                     LocalDate businessDate, LocalDate operatingDay, UUID sessionId, String sessionTimezone,
                     String hourly, String venuePrice, String fee, String total, boolean counted, String feeTreatment,
                     String terminalReason, Instant terminalAt, String name, String phone) {
    }

    static ManualRow row(UUID id) {
        return OWNER.sql("""
                        SELECT status, source, customer_id, start_at, end_at, created_at, business_date, operating_day,
                               operating_session_id, session_timezone, hourly_price_snapshot::text AS hourly,
                               venue_price_total::text AS venue_price, booking_fee_snapshot::text AS fee,
                               total_amount::text AS total, commitment_period IS NOT NULL AS counted, fee_treatment,
                               terminal_reason, terminal_at, manual_customer_name, manual_customer_phone
                          FROM booking.booking WHERE id = :id""")
                .param("id", id)
                .query((rs, n) -> new ManualRow(rs.getString("status"), rs.getString("source"),
                        rs.getObject("customer_id", UUID.class), at(rs.getObject("start_at", OffsetDateTime.class)),
                        at(rs.getObject("end_at", OffsetDateTime.class)),
                        at(rs.getObject("created_at", OffsetDateTime.class)),
                        rs.getObject("business_date", LocalDate.class), rs.getObject("operating_day", LocalDate.class),
                        rs.getObject("operating_session_id", UUID.class), rs.getString("session_timezone"),
                        rs.getString("hourly"), rs.getString("venue_price"), rs.getString("fee"),
                        rs.getString("total"), rs.getBoolean("counted"), rs.getString("fee_treatment"),
                        rs.getString("terminal_reason"), at(rs.getObject("terminal_at", OffsetDateTime.class)),
                        rs.getString("manual_customer_name"), rs.getString("manual_customer_phone")))
                .single();
    }

    record Reservation(UUID id, boolean active, Instant plannedStart, Instant plannedEnd, Instant releasedAt,
                       String reason) {
    }

    static Reservation reservation(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id, active_flag, planned_start, planned_end, actual_reservation_release_at, release_reason
                          FROM inventory.resource_reservation WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("id", bookingId)
                .query((rs, n) -> new Reservation(rs.getObject("id", UUID.class), rs.getBoolean("active_flag"),
                        at(rs.getObject("planned_start", OffsetDateTime.class)),
                        at(rs.getObject("planned_end", OffsetDateTime.class)),
                        at(rs.getObject("actual_reservation_release_at", OffsetDateTime.class)),
                        rs.getString("release_reason")))
                .single();
    }

    private static Instant at(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static ManualBookingRequest walkIn(OnlineBookingWorld world, int minutes) {
        return ManualBookingRequest.of(world.room.id(), PlayMode.SINGLE, null, minutes, null, null);
    }

    private static ManualBookingRequest future(OnlineBookingWorld world, Instant startAt, int minutes) {
        return ManualBookingRequest.of(world.room.id(), PlayMode.SINGLE, startAt, minutes, null, null);
    }

    private static JsonNode only(String type, UUID bookingId) throws Exception {
        List<String> payloads = events(type, bookingId);
        assertThat(payloads).as(type).hasSize(1);
        return JSON.readTree(payloads.get(0));
    }

    private static void assertRefused(Runnable action, ApiErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    private static long bookingsOf(Fixtures.Resource resource) {
        return OWNER.sql("SELECT count(*) FROM booking.booking WHERE resource_id = :id").param("id", resource.id())
                .query(Long.class).single();
    }

    // ---- creation ----------------------------------------------------------------------------------------------------

    @Test
    void aWalkInStartsActiveAtTheCommandInstantWithItsReservationCompletionJobAndBookingStarted() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        VenueBookingRow created = manual.create(world.account.id(), ManualBookingRequest.of(world.room.id(),
                PlayMode.SINGLE, null, 90, "  Omar  ", " 010 0123 4567 "));

        ManualRow row = row(created.bookingId());
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.source()).isEqualTo("MANUAL");
        assertThat(row.customerId()).isNull();
        assertThat(row.startAt()).as("startAt = the command instant").isEqualTo(row.createdAt());
        assertThat(row.endAt()).isEqualTo(row.startAt().plus(Duration.ofMinutes(90)));
        assertThat(row.counted()).as("no commitment (FR-MAN-07)").isFalse();
        assertThat(row.hourly()).isEqualTo("77.77");
        assertThat(row.venuePrice()).isEqualTo("116.66");
        assertThat(row.fee()).as("no customer fee (FR-MAN-08)").isEqualTo("0.00");
        assertThat(row.total()).isEqualTo("116.66");
        assertThat(row.feeTreatment()).isNull();
        assertThat(row.name()).isEqualTo("Omar");
        assertThat(row.phone()).isEqualTo("+201001234567");
        // inside the default session [t0-2h, t0+10h): its snapshot and operating day
        assertThat(row.sessionId()).isNotNull();
        assertThat(row.businessDate()).isEqualTo(row.operatingDay());

        Reservation reservation = reservation(created.bookingId());
        assertThat(reservation.active()).isTrue();
        assertThat(reservation.plannedStart()).isEqualTo(row.startAt());
        assertThat(reservation.plannedEnd()).isEqualTo(row.endAt());
        assertThat(job(ManualBookingCommand.bookingCompleteKey(created.bookingId())))
                .hasValueSatisfying(j -> {
                    assertThat(j.status()).isEqualTo("PENDING");
                    assertThat(j.dueAt()).isEqualTo(row.endAt());
                });
        assertThat(job(ManualBookingCommand.bookingStartKey(created.bookingId()))).isEmpty();
        List<HistoryRow> history = history(created.bookingId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).fromStatus()).isNull();
        assertThat(history.get(0).toStatus()).isEqualTo("ACTIVE");
        assertThat(history.get(0).actorType()).isEqualTo("VENUE");
        assertThat(history.get(0).actorId()).isEqualTo(world.account.id());
        assertThat(history.get(0).occurredAt()).isEqualTo(row.createdAt());

        JsonNode started = only(IntegrationEvents.BOOKING_STARTED, created.bookingId());
        assertThat(started.get("source").asText()).isEqualTo("MANUAL");
        assertThat(started.get("customerId").isNull()).isTrue();
        assertThat(started.get("venueId").asText()).isEqualTo(world.venue.id().toString());
        assertThat(events(IntegrationEvents.BOOKING_CREATED, created.bookingId())).as("never the request alert").isEmpty();
        assertThat(events(IntegrationEvents.RESERVATION_HELD, reservation.id())).hasSize(1);
        assertThat(OWNER.sql("SELECT count(*) FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", created.bookingId()).query(Long.class).single()).as("D9: attendance is E7-61's").isZero();
        assertThat(created.walkInName()).isEqualTo("Omar");
        assertThat(created.walkInPhone()).isEqualTo("+201001234567");
    }

    @Test
    void aFutureManualBookingIsConfirmedWithItsStartJobAndBookingConfirmed() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();

        VenueBookingRow created = manual.create(world.account.id(), future(world, start, 120));

        ManualRow row = row(created.bookingId());
        assertThat(row.status()).isEqualTo("CONFIRMED");
        assertThat(row.startAt()).isEqualTo(start);
        assertThat(row.name()).isNull();
        assertThat(row.phone()).isNull();
        assertThat(job(ManualBookingCommand.bookingStartKey(created.bookingId())))
                .hasValueSatisfying(j -> assertThat(j.dueAt()).isEqualTo(start));
        assertThat(job(ManualBookingCommand.bookingCompleteKey(created.bookingId()))).isEmpty();
        JsonNode confirmed = only(IntegrationEvents.BOOKING_CONFIRMED, created.bookingId());
        assertThat(confirmed.get("source").asText()).isEqualTo("MANUAL");
        assertThat(confirmed.get("customerId").isNull()).isTrue();
        assertThat(events(IntegrationEvents.BOOKING_STARTED, created.bookingId())).isEmpty();
        assertThat(history(created.bookingId())).singleElement()
                .satisfies(h -> assertThat(h.toStatus()).isEqualTo("CONFIRMED"));
    }

    // ---- business date and session snapshot (D1-D3, FR-MAN-09) ----------------------------------------------------

    /** D1 against the database: a walk-in an hour after the last session closed - no snapshot, the Cairo date. */
    @Test
    void aWalkInAfterTheVenueClosedHasNoSnapshotAndTheCairoDateOfItsStart() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().withoutSession().create();
        world.session(world.t0.minus(Duration.ofHours(13)).truncatedTo(ChronoUnit.MINUTES),
                world.t0.minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MINUTES), -1);

        ManualRow row = row(manual.create(world.account.id(), walkIn(world, 60)).bookingId());

        assertThat(row.sessionId()).isNull();
        assertThat(row.operatingDay()).isNull();
        assertThat(row.sessionTimezone()).isEqualTo("Africa/Cairo");
        assertThat(row.businessDate()).isEqualTo(LocalDate.ofInstant(row.startAt(), CAIRO));
    }

    /**
     * D2 against the database: the walk-in starts inside a session (labelled the day before, so the two dates differ)
     * but ends after it - no snapshot, the Cairo date of startAt, not the session's operating day.
     */
    @Test
    void aWalkInStraddlingTheSessionEndHasNoSnapshotAndTheCairoDate() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().withoutSession().create();
        Instant sessionStart = world.t0.minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MINUTES);
        world.session(sessionStart, world.t0.plus(Duration.ofMinutes(30)), -1);
        LocalDate label = LocalDateTime.ofInstant(sessionStart, CAIRO).toLocalDate().minusDays(1);

        ManualRow row = row(manual.create(world.account.id(), walkIn(world, 60)).bookingId());

        assertThat(row.sessionId()).isNull();
        assertThat(row.businessDate()).isEqualTo(LocalDate.ofInstant(row.startAt(), CAIRO)).isNotEqualTo(label);
    }

    /** The same session, a booking that fits it: the snapshot, and its operating day as the business date. */
    @Test
    void aBookingThatFitsTheSessionTakesItsSnapshotAndOperatingDay() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().withoutSession().create();
        Instant sessionStart = world.t0.minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MINUTES);
        world.session(sessionStart, world.t0.plus(Duration.ofHours(3)), -1);
        LocalDate label = LocalDateTime.ofInstant(sessionStart, CAIRO).toLocalDate().minusDays(1);

        ManualRow row = row(manual.create(world.account.id(), walkIn(world, 60)).bookingId());

        assertThat(row.sessionId()).isNotNull();
        assertThat(row.operatingDay()).isEqualTo(label);
        assertThat(row.businessDate()).isEqualTo(label);
    }

    // ---- the fifteen conditions and their codes (D4) --------------------------------------------------------------

    @Test
    void anOnboardingVenueIsForbiddenAndInactiveOrSuspendedVenuesAreNot() {
        OnlineBookingWorld onboarding = OnlineBookingWorld.builder().status(MarketplaceStatus.ONBOARDING).create();
        assertRefused(() -> manual.create(onboarding.account.id(), walkIn(onboarding, 60)), ApiErrorCode.FORBIDDEN);
        assertThat(bookingsOf(onboarding.room)).isZero();

        for (MarketplaceStatus status : List.of(MarketplaceStatus.INACTIVE, MarketplaceStatus.SUSPENDED)) {
            OnlineBookingWorld world = OnlineBookingWorld.builder().status(status).create();
            assertThat(manual.create(world.account.id(), walkIn(world, 60)).status().name()).as(status.name())
                    .isEqualTo("ACTIVE");
        }
    }

    /** D4: the token filter answers 401 first (HTTP test); the in-command check covers the race - FORBIDDEN. */
    @Test
    void aDisabledVenueAccountIsForbiddenInTheCommand() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().accountDisabled().create();

        assertRefused(() -> manual.create(world.account.id(), walkIn(world, 60)), ApiErrorCode.FORBIDDEN);
        assertThat(bookingsOf(world.room)).isZero();
    }

    @Test
    void aStartInThePastIsBackdatingAndAWrongDurationIsInvalid() {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        assertRefused(() -> manual.create(world.account.id(), future(world, OnlineBookingWorld.dbNow().minusSeconds(60),
                60)), ApiErrorCode.MANUAL_BOOKING_BACKDATING_NOT_ALLOWED);
        for (int minutes : new int[] {30, 75, 390}) {
            assertRefused(() -> manual.create(world.account.id(), walkIn(world, minutes)),
                    ApiErrorCode.BOOKING_DURATION_INVALID);
        }
        assertThat(bookingsOf(world.room)).isZero();
    }

    /** FR-MAN-10: no price - its own code, and no booking, reservation or event is written. */
    @Test
    void missingPricingCreatesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().unpriced().create();

        assertRefused(() -> manual.create(world.account.id(), walkIn(world, 60)),
                ApiErrorCode.MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING);
        assertThat(bookingsOf(world.room)).isZero();
        assertThat(OWNER.sql("SELECT count(*) FROM inventory.resource_reservation WHERE resource_id = :id")
                .param("id", world.room.id()).query(Long.class).single()).isZero();
    }

    @Test
    void theResourceMustBeActiveAndTakeThePlayModeItsActivityNeeds() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource disabled = world.anotherRoom(r -> r.playStation(ConsoleType.PS5).disabled());

        assertRefused(() -> manual.create(world.account.id(), ManualBookingRequest.of(world.room.id(), null, null, 60,
                null, null)), ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
        assertRefused(() -> manual.create(world.account.id(), ManualBookingRequest.of(disabled.id(), PlayMode.SINGLE,
                null, 60, null, null)), ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
    }

    @Test
    void anotherBookingOrMaintenanceOnTheIntervalIsSlotNoLongerAvailable() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        manual.create(world.account.id(), future(world, start, 120));

        assertRefused(() -> manual.create(world.account.id(), future(world, start.plus(Duration.ofMinutes(60)), 60)),
                ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);

        Fixtures.Resource other = world.anotherRoom(r -> r.playStation(ConsoleType.PS5));
        OWNER.sql("""
                INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
                VALUES (gen_random_uuid(), :resource, tstzrange(:start, :end, '[)'), 'E7-48 test', false)""")
                .param("resource", other.id())
                .param("start", OffsetDateTime.ofInstant(start, CAIRO))
                .param("end", OffsetDateTime.ofInstant(start.plus(Duration.ofHours(2)), CAIRO)).update();
        assertRefused(() -> manual.create(world.account.id(), ManualBookingRequest.of(other.id(), PlayMode.SINGLE,
                start.plus(Duration.ofMinutes(30)), 60, null, null)), ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
    }

    @Test
    void aSellingBlockNowAnswersItsOwnCode() {
        OnlineBookingWorld venueBlocked = OnlineBookingWorld.create();
        VenueAnswerDb.venueSellingBlockWithoutEmergency(venueBlocked.venue.id());
        assertRefused(() -> manual.create(venueBlocked.account.id(), walkIn(venueBlocked, 60)),
                ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);

        OnlineBookingWorld resourceBlocked = OnlineBookingWorld.create();
        VenueAnswerDb.resourceEmergency(resourceBlocked.venue.id(), resourceBlocked.room.id());
        assertRefused(() -> manual.create(resourceBlocked.account.id(), walkIn(resourceBlocked, 60)),
                ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
        VenueAnswerDb.endEmergencies(resourceBlocked.venue.id());
    }

    @Test
    void aResourceOfAnotherVenueOrNoneIsNotFound() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld other = OnlineBookingWorld.create();

        assertRefused(() -> manual.create(world.account.id(), walkIn(other, 60)), ApiErrorCode.NOT_FOUND);
        assertRefused(() -> manual.create(world.account.id(), ManualBookingRequest.of(UUID.randomUUID(),
                PlayMode.SINGLE, null, 60, null, null)), ApiErrorCode.NOT_FOUND);
    }

    @Test
    void twoWalkInsRacingForTheSameRoomLeaveExactlyOne() {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        ConcurrentRace.Result<UUID> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            start.await();
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                return manual.create(world.account.id(), walkIn(world, 60)).bookingId();
            }
        });

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE));
        assertThat(bookingsOf(world.room)).isOne();
    }

    // ---- cancellation (PCR-007, D5) ---------------------------------------------------------------------------------

    @Test
    void cancellingAConfirmedBookingFreesTheRoomSupersedesTheStartAndRecordsTheVenue() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID id = manual.create(world.account.id(), future(world, world.start(), 120)).bookingId();

        VenueBookingRow cancelled = manual.cancel(world.account.id(), id);

        assertCancelledAtOneInstant(world, id, "CONFIRMED");
        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThat(job(ManualBookingCommand.bookingStartKey(id)))
                .hasValueSatisfying(j -> assertThat(j.status()).isEqualTo("SUPERSEDED"));
    }

    /** PCR-007 through the real command: the commit-time provenance check passes with the venue's own history row. */
    @Test
    void cancellingAnActiveWalkInFreesTheRoomFromTheCancelInstantAndSupersedesItsCompletion() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID id = manual.create(world.account.id(), walkIn(world, 60)).bookingId();

        manual.cancel(world.account.id(), id);

        assertCancelledAtOneInstant(world, id, "ACTIVE");
        assertThat(job(ManualBookingCommand.bookingCompleteKey(id)))
                .hasValueSatisfying(j -> assertThat(j.status()).isEqualTo("SUPERSEDED"));
    }

    /** D5: CONFIRMED after its startAt (T4 has not run - E7-60's) is still cancelled; no time check. */
    @Test
    void aConfirmedBookingPastItsStartIsStillCancelled() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = OnlineBookingWorld.dbNow().plusSeconds(2);
        UUID id = manual.create(world.account.id(), future(world, start, 60)).bookingId();
        Deadlines.awaitDatabaseClockPast(start);

        manual.cancel(world.account.id(), id);

        assertThat(row(id).status()).isEqualTo("CANCELLED");
    }

    /**
     * PO decision 11 Oct 2026, E7-48, D12: an ACTIVE booking whose endAt has passed but whose BOOKING_COMPLETE has not
     * run is still ACTIVE, so the state-based rule cancels it, and its completion job is superseded. The walk-in is
     * written by the owner two hours back, with its BOOKING_COMPLETE still PENDING at the past endAt (not yet fired).
     */
    @Test
    void anActiveBookingPastItsEndWhoseCompletionHasNotRunIsCancelledAndItsCompletionSuperseded() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().withoutSession().create();
        UUID id = pastWalkIn(world, Duration.ofHours(2), 60);
        String complete = ManualBookingCommand.bookingCompleteKey(id);
        OWNER.sql("""
                INSERT INTO technical.scheduled_job (id, type, subject_id, subject_type, due_at, status,
                                                     idempotency_key, correlation_id)
                SELECT gen_random_uuid(), :type, b.id, 'BOOKING', b.end_at, 'PENDING', :key, gen_random_uuid()
                  FROM booking.booking b WHERE b.id = :id""")
                .param("type", ManualBookingCommand.BOOKING_COMPLETE).param("key", complete).param("id", id).update();
        assertThat(row(id).endAt()).isBefore(OnlineBookingWorld.dbNow());

        manual.cancel(world.account.id(), id);

        ManualRow row = row(id);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo("VENUE_CANCELLED_MANUAL_BOOKING");
        assertThat(row.terminalAt()).isAfter(row.endAt());
        assertThat(reservation(id).active()).isFalse();
        assertThat(job(complete)).hasValueSatisfying(j -> assertThat(j.status()).isEqualTo("SUPERSEDED"));
        assertThat(history(id).get(1).actorType()).isEqualTo("VENUE");
    }

    @Test
    void anOnlineBookingOrOneAlreadyEndedIsBookingStateChanged() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = online.create(world.request(world.start(), 60));
        assertRefused(() -> manual.cancel(world.account.id(), pending.bookingId()), ApiErrorCode.BOOKING_STATE_CHANGED);

        UUID cancelled = manual.create(world.account.id(), future(world, world.start().plus(Duration.ofHours(3)), 60))
                .bookingId();
        manual.cancel(world.account.id(), cancelled);
        assertRefused(() -> manual.cancel(world.account.id(), cancelled), ApiErrorCode.BOOKING_STATE_CHANGED);
        assertThat(history(cancelled)).hasSize(2);

        OnlineBookingWorld closed = OnlineBookingWorld.builder().withoutSession().create();
        UUID completed = pastWalkIn(closed, Duration.ofHours(3), 60);
        OWNER.sql("""
                WITH b AS (UPDATE booking.booking SET status = 'COMPLETED', terminal_at = end_at, version = version + 1
                            WHERE id = :id RETURNING id, end_at)
                UPDATE inventory.resource_reservation r SET active_flag = false,
                       actual_reservation_release_at = b.end_at, release_reason = 'COMPLETED'
                  FROM b WHERE r.booking_id = b.id""").param("id", completed).update();
        assertRefused(() -> manual.cancel(closed.account.id(), completed), ApiErrorCode.BOOKING_STATE_CHANGED);
    }

    /** The cancel response as this test stores it: no contact field, so no phone replay rule is needed. */
    record Cancelled(UUID bookingId, String status) {
    }

    /**
     * Category C (FRS 2.3): an account disabled after the request was authenticated (the token filter answers 401 to a
     * disabled account - ManualBookingHttpTest) is FORBIDDEN inside the command, through the real idempotent path, and
     * nothing is written: the booking, its reservation, its BOOKING_COMPLETE job, history, outbox and the key's record.
     */
    @Test
    void aVenueAccountDisabledAfterAuthenticationIsForbiddenAndNothingChanges() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID id = manual.create(world.account.id(), walkIn(world, 60)).bookingId();
        OWNER.sql("UPDATE iam.venue_account SET account_status = 'DISABLED' WHERE id = :id")
                .param("id", world.account.id()).update();
        String key = UUID.randomUUID().toString();
        IdempotentRequest request = idempotency.request(new AuthenticatedSubject(AuthSubjectType.VENUE,
                world.account.id()), "POST /api/v1/venue/bookings/{bookingId}/cancel-manual", UUID.fromString(key),
                Map.of("bookingId", id), null);

        assertRefused(() -> manual.cancel(world.account.id(), id, request, StoredResponse.of(Cancelled.class, 200),
                row -> new Cancelled(row.bookingId(), row.status().name())), ApiErrorCode.FORBIDDEN);

        assertThat(row(id).status()).isEqualTo("ACTIVE");
        assertThat(reservation(id).active()).isTrue();
        assertThat(job(ManualBookingCommand.bookingCompleteKey(id)))
                .hasValueSatisfying(j -> assertThat(j.status()).isEqualTo("PENDING"));
        assertThat(history(id)).hasSize(1);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).isEmpty();
        assertThat(VenueAnswerDb.idempotencyRecords(world.account.id(), key)).isZero();
    }

    @Test
    void aBookingOfAnotherVenueIsNotFound() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld other = OnlineBookingWorld.create();
        UUID id = manual.create(world.account.id(), future(world, world.start(), 60)).bookingId();

        assertRefused(() -> manual.cancel(other.account.id(), id), ApiErrorCode.NOT_FOUND);
        assertRefused(() -> manual.cancel(world.account.id(), UUID.randomUUID()), ApiErrorCode.NOT_FOUND);
        assertThat(row(id).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void twoCancellationsRacingLeaveOneCancellationAndOneStateChanged() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID id = manual.create(world.account.id(), walkIn(world, 60)).bookingId();

        ConcurrentRace.Result<VenueBookingRow> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            start.await();
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                return manual.cancel(world.account.id(), id);
            }
        });

        assertThat(race.values()).hasSize(1);
        assertThat(race.failures()).singleElement().isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
        assertThat(history(id)).hasSize(2);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).hasSize(1);
    }

    /** terminal_at = release = history occurred_at; VENUE actor; NOT_APPLICABLE; BookingCancelled MANUAL. */
    private static void assertCancelledAtOneInstant(OnlineBookingWorld world, UUID id, String from) throws Exception {
        ManualRow row = row(id);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo("VENUE_CANCELLED_MANUAL_BOOKING");
        assertThat(row.feeTreatment()).isEqualTo("NOT_APPLICABLE");
        Reservation reservation = reservation(id);
        assertThat(reservation.active()).isFalse();
        assertThat(reservation.releasedAt()).isEqualTo(row.terminalAt());
        assertThat(reservation.reason()).isEqualTo("VENUE_CANCELLED_MANUAL_BOOKING");
        HistoryRow last = history(id).get(1);
        assertThat(last.fromStatus()).isEqualTo(from);
        assertThat(last.toStatus()).isEqualTo("CANCELLED");
        assertThat(last.terminalReason()).isEqualTo("VENUE_CANCELLED_MANUAL_BOOKING");
        assertThat(last.actorType()).isEqualTo("VENUE");
        assertThat(last.actorId()).isEqualTo(world.account.id());
        assertThat(last.occurredAt()).isEqualTo(row.terminalAt());
        JsonNode event = only(IntegrationEvents.BOOKING_CANCELLED, id);
        assertThat(event.get("source").asText()).isEqualTo("MANUAL");
        assertThat(event.get("customerId").isNull()).isTrue();
        assertThat(event.get("terminalReason").asText()).isEqualTo("VENUE_CANCELLED_MANUAL_BOOKING");
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, reservation.id())).hasSize(1);
        assertThat(OWNER.sql("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = :id").param("id", id)
                .query(Long.class).single()).as("no customer, no fee decision").isZero();
    }

    /** An ACTIVE walk-in that started {@code ago} back, as the owner (its creation guards allow a past creation). */
    private static UUID pastWalkIn(OnlineBookingWorld world, Duration ago, int minutes) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                             console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                             nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                             booking_fee_snapshot, total_amount, business_date, session_timezone,
                                             created_at)
                SELECT :id, r.venue_id, r.id, NULL, 'MANUAL', 'ACTIVE', r.activity, r.console_type, 'SINGLE', r.name,
                       t.at, t.at + make_interval(mins => :minutes), :minutes, 60.00, round(60.00 * :minutes / 60.0, 2),
                       0.00, round(60.00 * :minutes / 60.0, 2), (t.at AT TIME ZONE 'Africa/Cairo')::date,
                       'Africa/Cairo', t.at
                  FROM resource.resource r, (SELECT now() - CAST(:ago AS interval) AS at) t
                 WHERE r.id = :resource""")
                .param("id", id).param("resource", world.room.id()).param("minutes", minutes)
                .param("ago", ago.toSeconds() + " seconds").update();
        OWNER.sql("""
                INSERT INTO inventory.resource_reservation (id, resource_id, reservation_type, booking_id,
                                                            planned_start, planned_end, actual_reservation_start)
                SELECT gen_random_uuid(), b.resource_id, 'BOOKING', b.id, b.start_at, b.end_at, b.start_at
                  FROM booking.booking b WHERE b.id = :id""").param("id", id).update();
        OWNER.sql("""
                INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind, from_status,
                                                     to_status, actor_type, actor_id, correlation_id)
                SELECT gen_random_uuid(), b.id, 1, b.created_at, 'STATUS_TRANSITION', NULL, 'ACTIVE', 'VENUE', :account,
                       gen_random_uuid()
                  FROM booking.booking b WHERE b.id = :id""").param("id", id).param("account", world.account.id())
                .update();
        return id;
    }
}
