package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.ok;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.bookingStart;
import static ps.e7gz.booking.internal.VenueAnswerDb.t1;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.booking.internal.LockWaits.Waiter;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.internal.OpeningHoursService;

/**
 * E7-113 Phase 4: the opening-hours commit end to end ({@code POST /api/v1/venue/opening-hours/commit}, FR-VEN-23
 * steps 6–9, FR-VEN-24…FR-VEN-28) on genuine bookings - created, accepted, expired and Late Confirmed by their own
 * commands - in a venue whose hours were saved through the same API: 24-hour sessions that open at a whole hour chosen
 * from the database clock so that the current session contains now with at least 10 hours left (Online intake needs
 * the venue open now and at least 10 minutes before the session ends, FR-BOK-08/09). Every instant is placed relative to
 * those sessions, so the fixture holds at any time of day; no business rule is relaxed. "Tomorrow" is the next
 * operating day; the change closes its weekday, so its bookings are newly outside the hours.
 *
 * <p>Fault injection is test-only and lives in the database of the test: a trigger this class creates and drops, scoped
 * to one test venue's rows, raising either right after the Loyalty write or at COMMIT. Nothing in production is changed
 * to make a test fail; no trigger is disabled.
 */
@AutoConfigureMockMvc
class OpeningHoursChangeIntegrationTest extends IntegrationTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(OpeningHoursChangeIntegrationTest.class);
    private static final String HOURS = "/api/v1/venue/opening-hours";
    private static final String ENDPOINT = "POST /api/v1/venue/opening-hours/commit";
    private static final String REASON = "VENUE_OPENING_HOURS_CHANGED";
    private static final String PIN = "4321";
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** Loyalty's own audit row of one Booking Fee decision ({@code loyalty.record_refund}). */
    private static final String FEE_DECIDED = "BOOKING_FEE_REFUND_DECIDED USER - -";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueResponses responses;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    VenueAnswers answers;

    @Autowired
    ManualBookings manualBookings;

    @Autowired
    CustomerBookings customerBookings;

    @Autowired
    LateConfirmations lateConfirmations;

    @Autowired
    OpeningHoursService hoursService;

    private CorrelationId.Scope correlation;
    private ExecutorService pool;
    private RetryLog retries;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(UUID.randomUUID());
        AtomicInteger threads = new AtomicInteger();
        pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "hours-race-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        retries = RetryLog.attach();
    }

    @AfterEach
    void close() throws InterruptedException {
        try {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("race threads ended").isTrue();
        } finally {
            retries.detach();
            correlation.close();
        }
    }

    // ================================================================ the mixed affected set, one transaction

    @Test
    void theCommitSavesTheHoursAndCancelsExactlyTheNewlyUncoveredBookingsInOneBatch() throws Exception {
        Venue v = venue();
        Instant slot = v.tomorrowAt(12);
        Booked pending = online(v, v.world.customer.id(), v.world.room, slot);
        Booked confirmed = confirmed(v, v.world.anotherCustomer(), v.room2, slot);
        Booked awaiting = awaiting(v, v.world.anotherCustomer(), v.room3, v.tomorrowAt(15));
        UUID manual = manual(v, v.world.room, v.tomorrowAt(18));
        // Not affected: another day, a booking the customer already cancelled, a VENUE_RESPONSE_EXPIRED one (it holds
        // no row), a Manual Booking made outside every session (beyond the horizon), today's booking (its session
        // has started and stays).
        Booked dayAfter = online(v, v.world.anotherCustomer(), v.world.room, v.dayAt(2, 12));
        Booked customerCancelled = online(v, v.world.anotherCustomer(), v.room2, v.tomorrowAt(20));
        customerBookings.cancel(customerCancelled.customerId(), customerCancelled.bookingId());
        UUID expired = new LateConfirmDb(runner, creation, store, answers)
                .expired(v.world, v.world.anotherCustomer(), v.room3, v.tomorrowAt(20), 60);
        UUID outsideSessions = manual(v, v.world.room, v.dayAt(200, 12));
        assertThat(OWNER.sql("SELECT operating_session_id IS NULL FROM booking.booking WHERE id = :id")
                .param("id", outsideSessions).query(Boolean.class).single()).isTrue();
        Booked today = online(v, v.world.anotherCustomer(), v.room2, v.todayLater());
        List<UUID> untouched = List.of(dayAfter.bookingId(), customerCancelled.bookingId(), expired, outsideSessions,
                today.bookingId());
        Map<UUID, Snapshot> untouchedBefore = snapshots(untouched);
        UUID tomorrowSession = sessionOf(pending.bookingId());

        Map<String, Object> change = v.closingTomorrow();
        JsonNode preview = preview(v, change).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerPasswordRequired").value(true))
                .andReturn().getResponse().getContentAsString().transform(this::data);
        assertThat(ids(preview)).containsExactlyInAnyOrder(pending.bookingId(), confirmed.bookingId(),
                awaiting.bookingId(), manual);

        UUID commitCorrelation = UUID.randomUUID();
        commit(v, change, preview.path("previewToken").asText(), PIN, UUID.randomUUID(), commitCorrelation)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.days[" + v.tomorrowWeekday() + "].mode").value("CLOSED"));

        Instant at = terminalAt(pending.bookingId());
        for (UUID id : List.of(pending.bookingId(), confirmed.bookingId(), awaiting.bookingId(), manual)) {
            assertCancelledByTheChange(v, id, at, commitCorrelation);
        }
        // One Loyalty batch: one FULL_REFUND per Online Booking, each on its terminal history row; none for Manual.
        List<Map<String, Object>> refunds = OWNER.sql("""
                        SELECT f.booking_id, f.treatment, f.user_id, f.correlation_id, h.terminal_reason
                          FROM loyalty.booking_fee_refund f
                          JOIN booking.booking_history h ON h.id = f.booking_history_id
                         WHERE f.booking_id IN (:ids)""")
                .param("ids", List.of(pending.bookingId(), confirmed.bookingId(), awaiting.bookingId(), manual))
                .query().listOfRows();
        assertThat(refunds).extracting(r -> r.get("booking_id")).containsExactlyInAnyOrder(pending.bookingId(),
                confirmed.bookingId(), awaiting.bookingId());
        assertThat(refunds).allSatisfy(r -> {
            assertThat(r.get("treatment")).isEqualTo("FULL_REFUND");
            assertThat(r.get("terminal_reason")).isEqualTo(REASON);
            assertThat(r.get("correlation_id")).isEqualTo(commitCorrelation);
        });
        assertThat(refunds).extracting(r -> r.get("user_id")).doesNotHaveDuplicates();
        assertThat(OWNER.sql("SELECT fee_treatment FROM booking.booking WHERE id = :id").param("id", manual)
                .query(String.class).single()).as("the reason's treatment snapshot, as the database maps it")
                .isEqualTo("FULL_REFUND");

        // Timers: T1 of the PENDING one, T4 of the CONFIRMED ones, T2/T3 of the AWAITING one - all withdrawn.
        assertThat(jobStatus(t1(pending.bookingId()))).isEqualTo("SUPERSEDED");
        assertThat(jobStatus(bookingStart(confirmed.bookingId()))).isEqualTo("SUPERSEDED");
        assertThat(jobStatus(bookingStart(manual))).isEqualTo("SUPERSEDED");
        assertThat(jobStatus(LateConfirmDb.t2(awaiting.bookingId()))).isEqualTo("SUPERSEDED");
        assertThat(jobStatus(LateConfirmDb.t3(awaiting.bookingId()))).isEqualTo("SUPERSEDED");
        // Late Confirm closed: the attempt without a Customer response, its hold released.
        LateConfirmDb.Attempt attempt = LateConfirmDb.attempt(awaiting.bookingId()).orElseThrow();
        assertThat(attempt.closedAt()).isEqualTo(at);
        assertThat(attempt.response()).isNull();
        assertThat(LateConfirmDb.reservations(awaiting.bookingId())).allSatisfy(r -> assertThat(r.active()).isFalse());

        // Events: exactly one BookingCancelled per cancelled booking (MANUAL for the Manual one) and one
        // ReservationReleased per released row; nothing for the hours change itself.
        assertThat(eventsOf(commitCorrelation)).containsExactlyInAnyOrder(
                IntegrationEvents.BOOKING_CANCELLED + " " + pending.bookingId(),
                IntegrationEvents.BOOKING_CANCELLED + " " + confirmed.bookingId(),
                IntegrationEvents.BOOKING_CANCELLED + " " + awaiting.bookingId(),
                IntegrationEvents.BOOKING_CANCELLED + " " + manual,
                IntegrationEvents.RESERVATION_RELEASED + " " + pending.reservationId(),
                IntegrationEvents.RESERVATION_RELEASED + " " + confirmed.reservationId(),
                IntegrationEvents.RESERVATION_RELEASED + " " + attempt.holdId(),
                IntegrationEvents.RESERVATION_RELEASED + " " + reservationOf(manual));
        assertThat(OWNER.sql("""
                        SELECT payload ->> 'source' FROM technical.outbox_event
                         WHERE event_type = :type AND aggregate_id = :id""")
                .param("type", IntegrationEvents.BOOKING_CANCELLED).param("id", manual).query(String.class).single())
                .isEqualTo("MANUAL");

        // The Owner Password: one SUCCESS row for the protected action; Loyalty's own row per fee decision; no audit
        // row for the hours change itself.
        assertThat(auditOf(commitCorrelation)).containsExactlyInAnyOrder(
                "OWNER_PASSWORD_ACCEPTED VENUE_ACCOUNT SUCCESS HOURS_CLOSURE_SAVE_CANCELLING_PROTECTED",
                FEE_DECIDED, FEE_DECIDED, FEE_DECIDED);

        // Historical session references are preserved: tomorrow's session row stays, referenced by the cancelled ones.
        assertThat(sessionOf(pending.bookingId())).isEqualTo(tomorrowSession);
        assertThat(OWNER.sql("SELECT count(*) FROM venue.operating_session WHERE id = :id")
                .param("id", tomorrowSession).query(Long.class).single()).isEqualTo(1);
        assertThat(OWNER.sql("SELECT count(*) FROM venue.operating_session WHERE venue_id = :v AND operating_day = :d")
                .param("v", v.world.venue.id()).param("d", v.today.plusDays(8)).query(Long.class).single())
                .as("next week's same weekday has no session any more").isZero();

        assertThat(snapshots(untouched)).as("unaffected and historical bookings unchanged").isEqualTo(untouchedBefore);
        assertThat(retries.serializationFailures()).isEmpty();
    }

    // ================================================================ Owner Password and the venue account

    @Test
    void pendingOnlineAndManualCancellationsCommitWithoutTheOwnerPassword() throws Exception {
        Venue v = venue();
        Booked pending = online(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        UUID manual = manual(v, v.room2, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        JsonNode preview = preview(v, change).andExpect(jsonPath("$.data.ownerPasswordRequired").value(false))
                .andReturn().getResponse().getContentAsString().transform(this::data);
        UUID correlationId = UUID.randomUUID();

        commit(v, change, preview.path("previewToken").asText(), null, UUID.randomUUID(), correlationId)
                .andExpect(status().isOk());

        Instant at = terminalAt(pending.bookingId());
        assertCancelledByTheChange(v, pending.bookingId(), at, correlationId);
        assertCancelledByTheChange(v, manual, at, correlationId);
        assertThat(auditOf(correlationId)).as("no Owner Password row, only Loyalty's fee decision")
                .containsExactly(FEE_DECIDED);
    }

    @Test
    void aProtectedCancellationNeedsTheOwnerPasswordMissingIsRequiredWrongIsInvalidAndAuditedNothingChanges()
            throws Exception {
        Venue v = venue();
        Booked confirmed = confirmed(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        String token = preview(v, change).andExpect(jsonPath("$.data.ownerPasswordRequired").value(true))
                .andReturn().getResponse().getContentAsString().transform(this::data).path("previewToken").asText();
        Snapshot before = snapshot(confirmed.bookingId());
        UUID key = UUID.randomUUID();

        UUID missing = UUID.randomUUID();
        expectError(commit(v, change, token, null, key, missing), 423, ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        assertThat(auditOf(missing)).as("no value sent is not a guess: nothing written").isEmpty();

        UUID wrong = UUID.randomUUID();
        expectError(commit(v, change, token, "9999", key, wrong), 423, ApiErrorCode.OWNER_PASSWORD_INVALID);
        assertThat(auditOf(wrong)).containsExactly(
                "OWNER_PASSWORD_REFUSED VENUE_ACCOUNT FAILURE HOURS_CLOSURE_SAVE_CANCELLING_PROTECTED");

        assertThat(snapshot(confirmed.bookingId())).as("nothing of either refused commit").isEqualTo(before);
        assertThat(v.version()).isEqualTo(1);
        assertThat(writesOf(missing) + writesOf(wrong)).isZero();
        assertThat(idempotencyRecords(v, key)).as("a refused request leaves no record: the key may be retried")
                .isZero();

        UUID right = UUID.randomUUID();
        commit(v, change, token, PIN, key, right).andExpect(status().isOk());
        assertCancelledByTheChange(v, confirmed.bookingId(), terminalAt(confirmed.bookingId()), right);
    }

    @Test
    void aBookingThatBecameProtectedAfterThePreviewNeedsTheOwnerPassword() throws Exception {
        Venue v = venue();
        Booked booked = online(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        String token = preview(v, change).andExpect(jsonPath("$.data.ownerPasswordRequired").value(false))
                .andReturn().getResponse().getContentAsString().transform(this::data).path("previewToken").asText();
        // The venue accepts it meanwhile: the same booking, room and interval (the token still matches), now CONFIRMED.
        assertThat(responses.accept(v.world.account.id(), booked.bookingId())).isInstanceOf(CommandAnswer.Ok.class);
        Snapshot before = snapshot(booked.bookingId());

        expectError(commit(v, change, token, null, UUID.randomUUID(), UUID.randomUUID()), 423,
                ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        assertThat(snapshot(booked.bookingId())).isEqualTo(before);
        assertThat(v.version()).isEqualTo(1);

        UUID correlationId = UUID.randomUUID();
        commit(v, change, token, PIN, UUID.randomUUID(), correlationId).andExpect(status().isOk());
        assertCancelledByTheChange(v, booked.bookingId(), terminalAt(booked.bookingId()), correlationId);
    }

    @Test
    void aDisabledVenueAccountIsRefusedBeforeAnyWrite() throws Exception {
        Venue v = venue();
        Booked pending = online(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        String token = previewToken(v, change);
        // A plain status write as the owner, triggers on: whatever the database cascades is in the snapshot taken after.
        OWNER.sql("UPDATE iam.venue_account SET account_status = 'DISABLED' WHERE id = :id")
                .param("id", v.world.account.id()).update();
        Snapshot before = snapshot(pending.bookingId());

        // The token filter refuses a disabled account first (401); the command's own check is the race behind it.
        assertThatThrownBy(() -> serviceCommit(v, change, token, null, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.FORBIDDEN));
        assertThat(snapshot(pending.bookingId())).isEqualTo(before);
        assertThat(v.version()).isEqualTo(1);
    }

    // ================================================================ stale previews and the lock set

    @Test
    void aBookingMadeAfterThePreviewIsAffectedSetChangedAndNothingChanges() throws Exception {
        Venue v = venue();
        Booked first = online(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        String token = previewToken(v, change);
        Booked later = online(v, v.world.anotherCustomer(), v.room2, v.tomorrowAt(12));
        Map<UUID, Snapshot> before = snapshots(List.of(first.bookingId(), later.bookingId()));

        UUID correlationId = UUID.randomUUID();
        expectError(commit(v, change, token, null, UUID.randomUUID(), correlationId), 409,
                ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW);

        assertThat(snapshots(List.of(first.bookingId(), later.bookingId()))).isEqualTo(before);
        assertThat(v.version()).isEqualTo(1);
        assertThat(writesOf(correlationId)).isZero();
        preview(v, change).andExpect(jsonPath("$.data.affected.length()").value(2));
    }

    /**
     * The commit names the Customers of the set it computed first. A new Customer's booking committed while the commit
     * waits for those locks is not in its lock set, so {@code begin_command} never lists or locks it: the set recomputed
     * under the venue lock has it, the token no longer matches, and the commit is refused - nothing cancelled, the new
     * booking untouched. Order is set by real locks: an owner connection holds the named Customer's row, the commit is
     * proved to wait there inside {@code begin_command} (before the venue), the genuine creation of the new booking
     * commits meanwhile, then the row is released.
     */
    @Test
    void aNewCustomersBookingCommittedWhileTheCommitWaitsForItsLocksIsNeverCancelledUnlocked() throws Exception {
        Venue v = venue();
        Booked named = online(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        String token = previewToken(v, change);
        UUID newcomer = v.world.anotherCustomer();
        Snapshot namedBefore = snapshot(named.bookingId());
        UUID commitCorrelation = UUID.randomUUID();
        Booked created;
        try (RowLock customer = RowLock.on("iam.user_account", named.customerId())) {
            Future<OpeningHoursService.View> committing = pool.submit(() -> inCorrelation(commitCorrelation,
                    () -> serviceCommit(v, change, token, null, UUID.randomUUID())));
            Waiter waiting = single(waitersBehind(customer.pid(), committing));
            assertThat(waiting.query()).contains("tournament.begin_command");
            assertThat(tupleLocks(waiting.pid())).containsExactly("iam.user_account");
            log.info("lock-set race: Customer row held by backend {}; commit backend {} waits: {}", customer.pid(),
                    waiting.pid(), waiting);

            created = online(v, newcomer, v.room2, v.tomorrowAt(12));      // commits while the commit waits

            customer.release();
            assertThatThrownBy(() -> failureOf(committing)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW));
        }

        assertThat(retries.serializationFailures()).as("the named Customer had no new booking: no 40001").isEmpty();
        assertThat(snapshot(named.bookingId())).isEqualTo(namedBefore);
        Snapshot newcomerBooking = snapshot(created.bookingId());
        assertThat(newcomerBooking.status()).isEqualTo("PENDING");
        assertThat(newcomerBooking.refunds()).isZero();
        assertThat(v.version()).isEqualTo(1);
        assertThat(writesOf(commitCorrelation)).isZero();
    }

    /**
     * A real SQLSTATE 40001: while the commit waits in {@code begin_command} on a named Customer, that Customer's
     * genuine new booking in another venue commits. {@code begin_command} raises 40001; the runner reruns the whole
     * transaction with the same scope; attempt 2 locks the new booking too, finds it outside the change and commits the
     * cancellation exactly once. Order: an owner connection holds the Customer's commitment guard, so the creation waits
     * holding only the Customer's row (begin_command locks users, then their guards, then venues); the commit's first
     * pass (venue only) runs, then the commit waits on the Customer behind the creation; the guard is released.
     */
    @Test
    void aReal40001WhileTheCommitWaitsIsRetriedByTheRunnerWithoutPartialWrites() throws Exception {
        Venue v = venue();
        Booked named = online(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        String token = previewToken(v, change);
        OnlineBookingWorld elsewhere = OnlineBookingWorld.create();
        UUID commitCorrelation = UUID.randomUUID();
        try (RowLock guard = RowLock.on("iam.user_commitment_guard", "user_id", named.customerId())) {
            Future<CreatedBooking> creating = pool.submit(() -> inCorrelation(UUID.randomUUID(), () ->
                    creation.create(elsewhere.request(named.customerId(), elsewhere.room, elsewhere.start(), 60))));
            Waiter creator = single(waitersBehind(guard.pid(), creating));
            assertThat(tupleLocks(creator.pid())).containsExactly("iam.user_commitment_guard");

            Future<OpeningHoursService.View> committing = pool.submit(() -> inCorrelation(commitCorrelation,
                    () -> serviceCommit(v, change, token, null, UUID.randomUUID())));
            Waiter waiting = single(waitersBehind(creator.pid(), committing));
            assertThat(waiting.query()).contains("tournament.begin_command");
            assertThat(tupleLocks(waiting.pid())).containsExactly("iam.user_account");
            log.info("40001 race: guard held by {}; creation {} waits; commit {} waits behind it", guard.pid(),
                    creator.pid(), waiting.pid());

            guard.release();
            creating.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(committing.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).version()).isEqualTo(2);
        }

        log.info("40001 race: runner lines {}", retries.serializationFailures());
        assertThat(retries.serializationFailures()).as("one genuine 40001, retried by the runner itself")
                .singleElement().satisfies(line -> assertThat(line).contains("attempt 1 failed with 40001; retrying"));
        assertCancelledByTheChange(v, named.bookingId(), terminalAt(named.bookingId()), commitCorrelation);
        assertThat(writesOf(commitCorrelation)).as("one history row, one refund, two events: written once")
                .isEqualTo(4);
    }

    // ================================================================ Idempotency-Key

    @Test
    void aRetryWithTheSameKeyReplaysTheAnswerAndWritesNothingTwice() throws Exception {
        Venue v = venue();
        Booked confirmed = confirmed(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        Booked pending = online(v, v.world.anotherCustomer(), v.room2, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        String token = previewToken(v, change);
        UUID key = UUID.randomUUID();
        UUID first = UUID.randomUUID();

        String answer = commit(v, change, token, PIN, key, first).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long writes = writesOf(first);
        Map<UUID, Snapshot> after = snapshots(List.of(confirmed.bookingId(), pending.bookingId()));
        assertThat(writes).as("2 history rows, 2 refunds, 4 events").isEqualTo(8);

        UUID replay = UUID.randomUUID();
        String replayed = commit(v, change, token, PIN, key, replay).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replayed).path("data")).isEqualTo(json.readTree(answer).path("data"));
        assertThat(writesOf(replay)).as("the replay ran nothing").isZero();
        assertThat(auditOf(replay)).isEmpty();
        assertThat(snapshots(List.of(confirmed.bookingId(), pending.bookingId()))).isEqualTo(after);
        assertThat(v.version()).isEqualTo(2);

        expectError(commit(v, v.closing(3), token, PIN, key, UUID.randomUUID()), 422,
                ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        expectError(commit(v, change, token, "1111", key, UUID.randomUUID()), 422,
                ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
        mvc.perform(post(HOURS + "/commit").header("Authorization", "Bearer " + v.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("proposal", change, "previewToken", token))))
                .andExpect(status().isBadRequest());
        assertThat(snapshots(List.of(confirmed.bookingId(), pending.bookingId()))).isEqualTo(after);
    }

    // ================================================================ atomicity: injected failures roll back everything

    @Test
    void aFailureRightAfterTheLoyaltyWriteRollsBackTheHoursEveryCancellationAndTheIdempotencyRecord()
            throws Exception {
        Venue v = venue();
        Booked pending = online(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        Booked confirmed = confirmed(v, v.world.anotherCustomer(), v.room2, v.tomorrowAt(12));
        UUID manual = manual(v, v.room3, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        String token = previewToken(v, change);
        Map<UUID, Snapshot> before = snapshots(List.of(pending.bookingId(), confirmed.bookingId(), manual));
        List<String> sessionsBefore = sessions(v);
        UUID key = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        try (FaultTrigger ignored = FaultTrigger.afterInsert("loyalty.booking_fee_refund", v.world.venue.id())) {
            assertThatThrownBy(() -> inCorrelation(correlationId, () -> serviceCommit(v, change, token, PIN, key)))
                    .hasStackTraceContaining(FaultTrigger.MESSAGE);
        }

        assertNothingCommitted(v, before, sessionsBefore, correlationId, key);
        UUID retried = UUID.randomUUID();
        inCorrelation(retried, () -> serviceCommit(v, change, token, PIN, key));
        assertCancelledByTheChange(v, pending.bookingId(), terminalAt(pending.bookingId()), retried);
        assertCancelledByTheChange(v, manual, terminalAt(manual), retried);
    }

    @Test
    void aFailureAtCommitAfterEveryWriteRollsBackTheHoursSessionsCancellationsRefundsEventsAndAudit() throws Exception {
        Venue v = venue();
        Booked pending = online(v, v.world.customer.id(), v.world.room, v.tomorrowAt(12));
        Booked awaiting = awaiting(v, v.world.anotherCustomer(), v.room2, v.tomorrowAt(15));
        UUID manual = manual(v, v.room3, v.tomorrowAt(12));
        Map<String, Object> change = v.closingTomorrow();
        String token = previewToken(v, change);
        Map<UUID, Snapshot> before = snapshots(List.of(pending.bookingId(), awaiting.bookingId(), manual));
        LateConfirmDb.Attempt attemptBefore = LateConfirmDb.attempt(awaiting.bookingId()).orElseThrow();
        List<String> sessionsBefore = sessions(v);
        UUID key = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        try (FaultTrigger ignored = FaultTrigger.atCommit("venue.venue_opening_template", v.world.venue.id())) {
            assertThatThrownBy(() -> inCorrelation(correlationId, () -> serviceCommit(v, change, token, PIN, key)))
                    .hasStackTraceContaining(FaultTrigger.MESSAGE);
        }

        assertNothingCommitted(v, before, sessionsBefore, correlationId, key);
        assertThat(LateConfirmDb.attempt(awaiting.bookingId())).contains(attemptBefore);
        assertThat(jobStatus(LateConfirmDb.t2(awaiting.bookingId()))).isEqualTo("PENDING");
        assertThat(auditOf(correlationId)).as("the Owner Password success row went with it").isEmpty();
    }

    // ================================================================ outcomes

    private void assertCancelledByTheChange(Venue v, UUID bookingId, Instant at, UUID correlationId) {
        Map<String, Object> row = OWNER.sql("""
                        SELECT b.status, b.terminal_reason, b.terminal_at = :at AS at_ok, b.fee_treatment, b.source,
                               (SELECT count(*) FROM booking.booking_history h
                                 WHERE h.booking_id = b.id AND h.terminal_reason IS NOT NULL) AS terminals,
                               (SELECT count(*) FROM booking.booking_history h
                                 WHERE h.booking_id = b.id AND h.terminal_reason = :reason AND h.actor_type = 'VENUE'
                                   AND h.actor_id = :account AND h.correlation_id = :c) AS ours,
                               (SELECT count(*) FROM loyalty.booking_fee_refund f WHERE f.booking_id = b.id) AS refunds,
                               (SELECT count(*) FROM inventory.resource_reservation r
                                 WHERE r.booking_id = b.id AND r.active_flag) AS active
                          FROM booking.booking b WHERE b.id = :id""")
                .param("id", bookingId).param("reason", REASON).param("account", v.world.account.id())
                .param("c", correlationId).param("at", java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
                .query().singleRow();
        assertThat(row.get("status")).as("%s", bookingId).isEqualTo("CANCELLED");
        assertThat(row.get("terminal_reason")).isEqualTo(REASON);
        assertThat(row.get("at_ok")).as("terminal_at is the command instant %s", at).isEqualTo(true);
        assertThat(row.get("terminals")).as("one terminal history row").isEqualTo(1L);
        assertThat(row.get("ours")).as("actor VENUE, the authenticated account, this command").isEqualTo(1L);
        assertThat(row.get("active")).as("no active reservation left").isEqualTo(0L);
        boolean online = "ONLINE".equals(row.get("source"));
        assertThat(row.get("fee_treatment")).as("the reason's treatment (booking.expected_fee_treatment)")
                .isEqualTo("FULL_REFUND");
        assertThat(row.get("refunds")).as("one fee decision per Online Booking, none for Manual")
                .isEqualTo(online ? 1L : 0L);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = :type AND aggregate_id = :id AND correlation_id = :c""")
                .param("type", IntegrationEvents.BOOKING_CANCELLED).param("id", bookingId).param("c", correlationId)
                .query(Long.class).single()).isEqualTo(1);
    }

    private void assertNothingCommitted(Venue v, Map<UUID, Snapshot> before, List<String> sessionsBefore,
                                        UUID correlationId, UUID key) {
        assertThat(snapshots(new ArrayList<>(before.keySet()))).as("every booking as before").isEqualTo(before);
        assertThat(v.version()).as("the hours as before").isEqualTo(1);
        assertThat(sessions(v)).as("the sessions as before").isEqualTo(sessionsBefore);
        assertThat(writesOf(correlationId)).as("no history, refund or event of the failed command").isZero();
        assertThat(auditOf(correlationId)).isEmpty();
        assertThat(idempotencyRecords(v, key)).as("nor its idempotency record").isZero();
    }

    private static void expectError(ResultActions call, int status, ApiErrorCode code) throws Exception {
        call.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code.name()));
    }

    // ================================================================ fixtures

    /**
     * A venue with saved 24-hour sessions opening at {@code open} (the current one started today and has at least 10
     * hours left), three PS5 rooms and a PIN. {@code today} is the current operating day.
     */
    private final class Venue {

        final OnlineBookingWorld world;
        final Fixtures.Resource room2;
        final Fixtures.Resource room3;
        final String token;
        final LocalDate today;
        final LocalTime open;

        Venue(OnlineBookingWorld world, Fixtures.Resource room2, Fixtures.Resource room3, String token,
              LocalDate today, LocalTime open) {
            this.world = world;
            this.room2 = room2;
            this.room3 = room3;
            this.token = token;
            this.today = today;
            this.open = open;
        }

        /** {@code hoursAfterOpen} (0…23) into the session of the operating day {@code days} after today. */
        Instant dayAt(int days, int hoursAfterOpen) {
            return today.plusDays(days).atTime(open).plusHours(hoursAfterOpen).atZone(CAIRO).toInstant();
        }

        Instant tomorrowAt(int hoursAfterOpen) {
            return dayAt(1, hoursAfterOpen);
        }

        /** A whole hour at least 30 minutes away, inside the current (started) session. */
        Instant todayLater() {
            Instant now = OnlineBookingWorld.dbNow().plus(Duration.ofMinutes(30));
            Instant start = now.atZone(CAIRO).plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant();
            assertThat(start.plus(Duration.ofHours(1))).as("inside the current session").isBefore(dayAt(1, 0));
            return start;
        }

        Map<String, Object> proposal(List<Map<String, Object>> days) {
            return OpeningHoursChangeIntegrationTest.proposal(open, days);
        }

        int tomorrowWeekday() {
            return today.plusDays(1).getDayOfWeek().getValue() - 1;
        }

        Map<String, Object> closingTomorrow() {
            return closing(1);
        }

        Map<String, Object> closing(int days) {
            return proposal(List.of(day(today.plusDays(days).getDayOfWeek().getValue() - 1, "CLOSED")));
        }

        long version() {
            return OWNER.sql("SELECT version FROM venue.venue_opening_template WHERE venue_id = :v")
                    .param("v", world.venue.id()).query(Long.class).single();
        }
    }

    private Venue venue() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.builder().withoutSession().create();
        Fixtures.Resource room2 = world.anotherRoom(room -> room.playStation(ConsoleType.PS5));
        Fixtures.Resource room3 = world.anotherRoom(room -> room.playStation(ConsoleType.PS5));
        OWNER.sql("""
                        INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at, version)
                        VALUES (:account, :hash, now() - interval '1 day', 0)""")
                .param("account", world.account.id()).param("hash", passwords.encode(PIN)).update();
        // The sessions open at max(0, hour now - 12): the current session (today's) started at most 12h59m ago, so it
        // has at least 11 hours left (10 across a spring-forward change) and the next operating day's session starts
        // that far ahead - at any time of day. The fixture needs only a few of those hours (todayLater, intake cutoff).
        java.time.LocalDateTime now = java.time.LocalDateTime.ofInstant(OnlineBookingWorld.dbNow(), CAIRO);
        LocalTime open = LocalTime.of(Math.max(0, now.getHour() - 12), 0);
        Venue v = new Venue(world, room2, room3, accessTokens.issue(AuthSubjectType.VENUE, world.account.id()),
                now.toLocalDate(), open);
        Map<String, Object> fullDays = v.proposal(List.of());
        String token = preview(v, fullDays).andExpect(jsonPath("$.data.affected.length()").value(0))
                .andReturn().getResponse().getContentAsString().transform(this::data).path("previewToken").asText();
        commit(v, fullDays, token, null, UUID.randomUUID(), UUID.randomUUID()).andExpect(status().isOk());
        assertThat(v.version()).isEqualTo(1);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM venue.operating_session
                         WHERE venue_id = :v AND operating_day = :d AND session_period @> clock_timestamp()
                           AND upper(session_period) > clock_timestamp() + interval '4 hours'""")
                .param("v", world.venue.id()).param("d", v.today).query(Long.class).single())
                .as("today's session is open now with hours left").isEqualTo(1);
        return v;
    }

    private static Map<String, Object> proposal(LocalTime open, List<Map<String, Object>> days) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("defaultOpen", open + ":00");
        proposal.put("defaultClose", open + ":00");
        proposal.put("defaultFullDay", true);
        proposal.put("days", days);
        return proposal;
    }

    private static Map<String, Object> day(int weekday, String mode) {
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("weekday", weekday);
        day.put("mode", mode);
        day.put("customOpen", null);
        day.put("customClose", null);
        return day;
    }

    /** A genuine booking and the facts the assertions need. */
    private record Booked(UUID bookingId, UUID reservationId, UUID customerId) {
    }

    private Booked online(Venue v, UUID customerId, Fixtures.Resource room, Instant start) {
        CreatedBooking created = creation.create(v.world.request(customerId, room, start, 60));
        return new Booked(created.bookingId(), created.reservationId(), customerId);
    }

    private Booked confirmed(Venue v, UUID customerId, Fixtures.Resource room, Instant start) {
        Booked booked = online(v, customerId, room, start);
        assertThat(responses.accept(v.world.account.id(), booked.bookingId())).isInstanceOf(CommandAnswer.Ok.class);
        assertThat(jobStatus(bookingStart(booked.bookingId()))).isEqualTo("PENDING");
        return booked;
    }

    /** Created, expired by T1, then Late Confirmed by the venue: AWAITING with an open attempt and its hold. */
    private Booked awaiting(Venue v, UUID customerId, Fixtures.Resource room, Instant start) {
        UUID id = new LateConfirmDb(runner, creation, store, answers).expired(v.world, customerId, room, start, 60);
        ok(lateConfirmations.initiate(v.world.account.id(), id));
        assertThat(VenueAnswerDb.booking(id).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        return new Booked(id, LateConfirmDb.attempt(id).orElseThrow().holdId(), customerId);
    }

    private UUID manual(Venue v, Fixtures.Resource room, Instant start) {
        return manualBookings.create(v.world.account.id(),
                ManualBookingRequest.of(room.id(), PlayMode.SINGLE, start, 60, null, null)).bookingId();
    }

    // ================================================================ calls

    private ResultActions preview(Venue v, Map<String, Object> proposal) throws Exception {
        return mvc.perform(post(HOURS + "/preview").header("Authorization", "Bearer " + v.token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("proposal", proposal))));
    }

    private String previewToken(Venue v, Map<String, Object> proposal) throws Exception {
        return preview(v, proposal).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
                .transform(this::data).path("previewToken").asText();
    }

    private ResultActions commit(Venue v, Map<String, Object> proposal, String previewToken, String ownerPassword,
                                 UUID key, UUID correlationId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("proposal", proposal);
        body.put("previewToken", previewToken);
        if (ownerPassword != null) {
            body.put("ownerPassword", ownerPassword);
        }
        return mvc.perform(post(HOURS + "/commit").header("Authorization", "Bearer " + v.token)
                .header("Idempotency-Key", key.toString()).header("X-Correlation-Id", correlationId.toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** The service the controller calls, from a test thread: the same commit, with a request fingerprint of its own. */
    private OpeningHoursService.View serviceCommit(Venue v, Map<String, Object> proposal, String previewToken,
                                                   String ownerPassword, UUID key) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) proposal.get("days");
        LocalTime open = LocalTime.parse((String) proposal.get("defaultOpen"));
        OpeningHoursService.Proposal hours = new OpeningHoursService.Proposal(open, open, true,
                days.stream().map(d -> new OpeningHoursService.Day((Integer) d.get("weekday"),
                        ps.e7gz.common.codes.OpeningDayMode.valueOf((String) d.get("mode")), null, null)).toList());
        String fingerprint = String.format("%064x", new java.math.BigInteger(1,
                (proposal + "|" + previewToken + "|" + ownerPassword).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .mod(java.math.BigInteger.TWO.pow(256)));
        IdempotentRequest request = new IdempotentRequest(ActorType.VENUE, v.world.account.id(), ENDPOINT,
                key.toString(), fingerprint);
        return hoursService.commit(v.world.account.id(), hours, previewToken, ownerPassword, request,
                StoredResponse.of(OpeningHoursService.View.class, 200), view -> view).body();
    }

    private JsonNode data(String response) {
        try {
            return json.readTree(response).path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<UUID> ids(JsonNode preview) {
        List<UUID> ids = new ArrayList<>();
        preview.path("affected").forEach(row -> ids.add(UUID.fromString(row.path("bookingId").asText())));
        return ids;
    }

    // ================================================================ committed reads

    /** Every committed fact of one booking a commit could change. */
    record Snapshot(String status, String terminalReason, String feeTreatment, long history, long refunds,
                    long activeReservations, long events, String t1, String t4) {
    }

    private static Snapshot snapshot(UUID id) {
        return OWNER.sql("""
                        SELECT b.status, b.terminal_reason, b.fee_treatment,
                               (SELECT count(*) FROM booking.booking_history h WHERE h.booking_id = b.id) AS history,
                               (SELECT count(*) FROM loyalty.booking_fee_refund f WHERE f.booking_id = b.id) AS refunds,
                               (SELECT count(*) FROM inventory.resource_reservation r
                                 WHERE r.booking_id = b.id AND r.active_flag) AS active,
                               (SELECT count(*) FROM technical.outbox_event e
                                 WHERE e.aggregate_id = b.id
                                    OR e.aggregate_id IN (SELECT r.id FROM inventory.resource_reservation r
                                                           WHERE r.booking_id = b.id)) AS events
                          FROM booking.booking b WHERE b.id = :id""")
                .param("id", id)
                .query((rs, n) -> new Snapshot(rs.getString("status"), rs.getString("terminal_reason"),
                        rs.getString("fee_treatment"), rs.getLong("history"), rs.getLong("refunds"),
                        rs.getLong("active"), rs.getLong("events"), jobStatus(t1(id)), jobStatus(bookingStart(id))))
                .single();
    }

    private static Map<UUID, Snapshot> snapshots(List<UUID> ids) {
        Map<UUID, Snapshot> all = new LinkedHashMap<>();
        ids.forEach(id -> all.put(id, snapshot(id)));
        return all;
    }

    private static String jobStatus(String key) {
        return VenueAnswerDb.job(key).map(VenueAnswerDb.JobRow::status).orElse(null);
    }

    private static Instant terminalAt(UUID id) {
        return OWNER.sql("SELECT terminal_at FROM booking.booking WHERE id = :id").param("id", id)
                .query(java.time.OffsetDateTime.class).single().toInstant();
    }

    private static UUID sessionOf(UUID id) {
        return OWNER.sql("SELECT operating_session_id FROM booking.booking WHERE id = :id").param("id", id)
                .query(UUID.class).single();
    }

    private static UUID reservationOf(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING'""")
                .param("id", bookingId).query(UUID.class).single();
    }

    private static List<String> sessions(Venue v) {
        return OWNER.sql("""
                        SELECT id || ' ' || operating_day || ' ' || generated_from_version FROM venue.operating_session
                         WHERE venue_id = :v ORDER BY operating_day, id""")
                .param("v", v.world.venue.id()).query(String.class).list();
    }

    private static List<String> eventsOf(UUID correlationId) {
        return OWNER.sql("""
                        SELECT event_type || ' ' || aggregate_id FROM technical.outbox_event
                         WHERE correlation_id = :c""")
                .param("c", correlationId).query(String.class).list();
    }

    private static List<String> auditOf(UUID correlationId) {
        return OWNER.sql("""
                        SELECT action || ' ' || subject_type || ' ' || coalesce(owner_password_outcome, '-') || ' '
                               || coalesce(payload ->> 'protectedAction', '-')
                          FROM audit.audit_event WHERE correlation_id = :c ORDER BY occurred_at""")
                .param("c", correlationId).query(String.class).list();
    }

    /** Committed rows carrying one correlation: booking history, Booking Fee decisions and outbox events. */
    private static long writesOf(UUID correlationId) {
        return OWNER.sql("""
                        SELECT (SELECT count(*) FROM booking.booking_history WHERE correlation_id = :c)
                             + (SELECT count(*) FROM loyalty.booking_fee_refund WHERE correlation_id = :c)
                             + (SELECT count(*) FROM technical.outbox_event WHERE correlation_id = :c)""")
                .param("c", correlationId).query(Long.class).single();
    }

    private static long idempotencyRecords(Venue v, UUID key) {
        return VenueAnswerDb.idempotencyRecords(v.world.account.id(), key.toString());
    }

    private static List<String> tupleLocks(int pid) {
        return OWNER.sql("""
                        SELECT n.nspname || '.' || c.relname
                          FROM pg_locks l
                          JOIN pg_class c ON c.oid = l.relation
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE l.pid = :pid AND l.locktype = 'tuple' AND l.granted
                         ORDER BY 1""")
                .param("pid", pid).query(String.class).list();
    }

    // ================================================================ races and faults (test-only)

    /** The waiters behind {@code holderPid} once {@code waiter} waits, re-read on a fresh activity snapshot. */
    private static List<Waiter> waitersBehind(int holderPid, Future<?> waiter) {
        List<Waiter> first = LockWaits.awaitBlockedBy(holderPid, waiter, TIMEOUT);
        List<Waiter> settled = LockWaits.blockedBy(holderPid);
        assertThat(settled).extracting(Waiter::pid).isEqualTo(first.stream().map(Waiter::pid).toList());
        return settled;
    }

    private static Waiter single(List<Waiter> waiting) {
        assertThat(waiting).as("exactly one backend waiting").hasSize(1);
        Waiter waiter = waiting.getFirst();
        assertThat(waiter.waitEventType()).isEqualTo("Lock");
        return waiter;
    }

    private static <R> R inCorrelation(UUID id, Supplier<R> body) {
        try (CorrelationId.Scope ignored = CorrelationId.open(id)) {
            return body.get();
        }
    }

    private static Object failureOf(Future<?> racer) throws Throwable {
        Object value;
        try {
            value = racer.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("the racer did not end within " + TIMEOUT, e);
        }
        throw new AssertionError("expected a refusal, got " + value);
    }

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR);
    }

    /** TEST-ONLY: an ordinary row lock held by an owner connection in an open transaction that writes nothing. */
    private static final class RowLock implements AutoCloseable {

        private final Connection connection;
        private final int pid;
        private boolean released;

        private RowLock(Connection connection, int pid) {
            this.connection = connection;
            this.pid = pid;
        }

        static RowLock on(String table, UUID id) throws SQLException {
            return on(table, "id", id);
        }

        static RowLock on(String table, String key, UUID id) throws SQLException {
            Connection connection = owner();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT 1 FROM " + table + " WHERE " + key + " = ? FOR UPDATE")) {
                    lock.setObject(1, id);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("no row " + id + " in " + table + " to hold");
                        }
                    }
                }
                try (PreparedStatement self = connection.prepareStatement("SELECT pg_backend_pid()");
                     ResultSet rs = self.executeQuery()) {
                    rs.next();
                    return new RowLock(connection, rs.getInt(1));
                }
            } catch (SQLException | RuntimeException e) {
                connection.close();
                throw e;
            }
        }

        int pid() {
            return pid;
        }

        void release() throws SQLException {
            if (!released) {
                released = true;
                connection.rollback();
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                release();
            } finally {
                connection.close();
            }
        }
    }

    /**
     * TEST-ONLY fault injection: a trigger this test creates on {@code table} that raises for one test venue's rows
     * only - at once after the insert, or as a deferred constraint trigger at
     * COMMIT, after every write of the transaction. Dropped on close. No production trigger is touched or disabled.
     */
    private static final class FaultTrigger implements AutoCloseable {

        static final String MESSAGE = "E7-113 test-injected failure";
        private static final String FUNCTION = "public.e7_113_test_fault";

        private final String table;
        private final String name;

        private FaultTrigger(String table, String name) {
            this.table = table;
            this.name = name;
        }

        /** Raises right after a row of {@code table} whose {@code booking_id} is a booking of {@code venueId}. */
        static FaultTrigger afterInsert(String table, UUID venueId) {
            return create(table, "CREATE TRIGGER %s AFTER INSERT ON %s FOR EACH ROW EXECUTE FUNCTION %s('%s', '%s')",
                    venueId, "booking");
        }

        /** Raises at COMMIT for a row of {@code table} whose {@code venue_id} is {@code venueId}. */
        static FaultTrigger atCommit(String table, UUID venueId) {
            return create(table, "CREATE CONSTRAINT TRIGGER %s AFTER INSERT OR UPDATE ON %s DEFERRABLE INITIALLY "
                    + "DEFERRED FOR EACH ROW EXECUTE FUNCTION %s('%s', '%s')", venueId, "venue");
        }

        private static FaultTrigger create(String table, String template, UUID venueId, String scope) {
            String name = "e7_113_test_fault_" + UUID.randomUUID().toString().replace("-", "");
            OWNER.sql("CREATE OR REPLACE FUNCTION " + FUNCTION + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF TG_ARGV[1] = 'venue' THEN "
                    + "  IF NEW.venue_id = TG_ARGV[0]::uuid THEN RAISE EXCEPTION '" + MESSAGE + "'; END IF; "
                    + "ELSIF EXISTS (SELECT 1 FROM booking.booking b "
                    + "               WHERE b.id = NEW.booking_id AND b.venue_id = TG_ARGV[0]::uuid) THEN "
                    + "  RAISE EXCEPTION '" + MESSAGE + "'; "
                    + "END IF; RETURN NULL; END $$").update();
            OWNER.sql(String.format(template, name, table, FUNCTION, venueId, scope)).update();
            return new FaultTrigger(table, name);
        }

        @Override
        public void close() {
            OWNER.sql("DROP TRIGGER IF EXISTS " + name + " ON " + table).update();
            OWNER.sql("DROP FUNCTION IF EXISTS " + FUNCTION + "()").update();
        }
    }

    /** The runner's own DEBUG/WARN lines for SQLSTATE 40001, captured for one test. */
    private static final class RetryLog {

        private final Logger logger;
        private final Level previous;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private RetryLog(Logger logger) {
            this.logger = logger;
            this.previous = logger.getLevel();
        }

        static RetryLog attach() {
            RetryLog log = new RetryLog((Logger) LoggerFactory.getLogger(CommandRunner.class));
            log.appender.start();
            log.logger.addAppender(log.appender);
            log.logger.setLevel(Level.DEBUG);
            return log;
        }

        List<String> serializationFailures() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("40001")).toList();
        }

        void detach() {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }
}
