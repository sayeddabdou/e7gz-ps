package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.BookingFlows.OWNER_PIN;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.identity.api.OwnerPasswordAttempts;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.CommitFault;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-114 over HTTP: {@code POST /venue/bookings/{bookingId}/cancel-confirmed} through the real security chain,
 * Idempotency-Key handling (E7-42) and error envelope, with the Sprint 3 contract's shape - the {@code VenueBooking} on
 * success, 423 for a missing or wrong Owner PIN, 429 once locked out, 409 for a booking no longer CONFIRMED, 404 for
 * another venue's booking, 401 for a disabled account; replay, key reuse and concurrent requests; and no PIN in a log,
 * response, audit row, event or idempotency record. Replaces E7-114's line of {@code Sprint3StubHttpTest}.
 */
@AutoConfigureMockMvc
class VenueCancelConfirmedHttpTest extends IntegrationTest {

    static final String BOOKINGS = "/api/v1/venue/bookings/";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationContext context;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueResponses responses;

    private BookingFlows flows;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        flows = BookingFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void theRightPinCancelsAndAnswersTheVenueBookingAndAReplayAnswersTheSameWithoutRunningAgain() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String key = UUID.randomUUID().toString();

        MvcResult first = mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN)))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN)))
                .andExpect(status().isOk()).andReturn();

        JsonNode data = body(first).get("data");
        assertThat(data.get("bookingId").asText()).isEqualTo(created.bookingId().toString());
        assertThat(data.get("source").asText()).isEqualTo("ONLINE");
        assertThat(data.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(data.get("terminalReason").asText()).isEqualTo("VENUE_CANCELLED_CONFIRMED_BOOKING");
        assertThat(data.at("/customer/username").isTextual()).isTrue();
        assertThat(data.has("ownerPassword")).isFalse();
        assertThat(first.getResponse().getContentAsString()).doesNotContain(OWNER_PIN);
        assertThat(body(replay).get("data")).isEqualTo(data);
        assertThat(history(created.bookingId())).as("ran once").hasSize(3);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).hasSize(1);
    }

    @Test
    void theIdempotencyKeyIsRequired() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);

        mvc.perform(cancel(venue(world), created, null, pin(OWNER_PIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
        assertThat(booking(created.bookingId()).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void aMissingPinIsRequiredAndAWrongOneIsInvalidBoth423AndTheKeyStaysUsable() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String key = UUID.randomUUID().toString();

        mvc.perform(cancel(venue(world), created, key, "{}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        mvc.perform(cancel(venue(world), created, key, pin("")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        MvcResult wrong = mvc.perform(cancel(venue(world), created, key, pin("9999")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID")).andReturn();
        assertThat(wrong.getResponse().getContentAsString()).doesNotContain("9999");
        assertThat(booking(created.bookingId()).status()).isEqualTo("CONFIRMED");

        mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void aBookingNoLongerConfirmedIsBookingStateChanged() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = creation.create(world.request(world.anotherCustomer(), world.room, world.start(), 60));
        flows.ownerPin(world.account.id());

        mvc.perform(cancel(venue(world), created, UUID.randomUUID().toString(), pin(OWNER_PIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BOOKING_STATE_CHANGED"));
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
        // PO decision Q2: the right PIN, then the refusal - one OWNER_PASSWORD_ACCEPTED row of the gate, not counted
        assertThat(accepted(world.account.id()))
                .containsExactly("SUCCESS CANCEL_CONFIRMED_ONLINE_BOOKING BOOKING_STATE_CHANGED");
        assertThat(refusals(world.account.id())).isEmpty();
        assertThat(failures(world.account.id())).isZero();
    }

    /**
     * A database guard firing only at COMMIT (a deferred trigger) after the right PIN: answered with its code, never
     * 500 (PO decision 25 Oct 2026, E7-114 follow-up); one OWNER_PASSWORD_ACCEPTED row with that code, not counted;
     * the cancellation, its history, refund decision, events, success row and idempotency record all roll back.
     */
    @Test
    void aGuardFailingTheCommitAfterTheRightPinIsAnsweredWithItsCodeAndNothingIsCancelled() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String key = UUID.randomUUID().toString();
        Writes before = writes(world, created, key);

        try (CommitFault ignored = CommitFault.onRowsOf("booking.booking", "id", created.bookingId(),
                "gd_reservation_conflict_booking")) {
            mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SLOT_NO_LONGER_AVAILABLE"));
        }

        assertThat(booking(created.bookingId()).status()).isEqualTo("CONFIRMED");
        assertThat(writes(world, created, key)).isEqualTo(before);
        assertThat(accepted(world.account.id()))
                .containsExactly("SUCCESS CANCEL_CONFIRMED_ONLINE_BOOKING SLOT_NO_LONGER_AVAILABLE");
        assertThat(refusals(world.account.id())).isEmpty();
        assertThat(failures(world.account.id())).isZero();
    }

    /** Q2: a request refused before the PIN check (another venue's booking) leaves no acceptance row. */
    @Test
    void aRefusalBeforeThePinCheckIsNotAuditedAsAccepted() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        OnlineBookingWorld other = OnlineBookingWorld.create();
        flows.ownerPin(other.account.id());

        mvc.perform(cancel(venue(other), created, UUID.randomUUID().toString(), pin(OWNER_PIN)))
                .andExpect(status().isNotFound());

        assertThat(accepted(other.account.id())).isEmpty();
        assertThat(refusals(other.account.id())).isEmpty();
    }

    @Test
    void anotherVenuesBookingIsNotFound() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        OnlineBookingWorld other = OnlineBookingWorld.create();
        flows.ownerPin(other.account.id());

        mvc.perform(cancel(venue(other), created, UUID.randomUUID().toString(), pin(OWNER_PIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(booking(created.bookingId()).status()).isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------------------------------ Phase 2: security

    /**
     * PCR-006, arch 10 section 7: five wrong PINs in 15 minutes lock the venue account; each is 423 INVALID and audited
     * as a counted refusal. The sixth attempt - even with the right PIN - is 429 RATE_LIMITED, audited with
     * {@code lockedOut} true and not counted again. Nothing is cancelled. The lockout policy is E7-24's, unchanged; the
     * shared gate now checks the PIN before the Idempotency-Key lookup (PO decision Q3, 24 Oct 2026).
     */
    @Test
    void fiveWrongPinsLockTheAccountAndEvenTheRightPinIsThenRateLimited() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);

        for (int i = 0; i < OwnerPasswordAttempts.MAX_FAILURES; i++) {
            mvc.perform(cancel(venue(world), created, UUID.randomUUID().toString(), pin("9999")))
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        }
        mvc.perform(cancel(venue(world), created, UUID.randomUUID().toString(), pin(OWNER_PIN)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(refusals(world.account.id())).hasSize(OwnerPasswordAttempts.MAX_FAILURES + 1)
                .allSatisfy(row -> assertThat(row).startsWith("FAILURE CANCEL_CONFIRMED_ONLINE_BOOKING"))
                .filteredOn(row -> row.endsWith("true")).as("only the locked-out attempt").hasSize(1);
        assertThat(booking(created.bookingId()).status()).isEqualTo("CONFIRMED");
        assertThat(successAudit(created.bookingId())).isZero();
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).isEmpty();
    }

    /**
     * NFR-21, arch 09 section 4.3: neither the right nor a wrong PIN reaches the logs, a response, an audit row, an
     * outbox event or the idempotency record (whose fingerprint holds the PIN only as an HMAC). Checked on the request
     * text, never on bare digits, which a UUID or a timestamp could contain by chance.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void noPinReachesTheLogsAResponseAnAuditRowAnEventOrTheIdempotencyRecord(CapturedOutput output)
            throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String key = UUID.randomUUID().toString();

        String wrong = mvc.perform(cancel(venue(world), created, key, pin("9999"))).andExpect(status().isLocked())
                .andReturn().getResponse().getContentAsString();
        String right = mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String body : List.of(wrong, right, replay)) {
            assertThat(body).doesNotContain("ownerPassword").doesNotContain("\"" + OWNER_PIN + "\"")
                    .doesNotContain("\"9999\"");
        }
        assertThat(output.getAll()).doesNotContain(pin(OWNER_PIN), pin("9999"), "ownerPassword=" + OWNER_PIN,
                "\"" + OWNER_PIN + "\"", "\"9999\"");
        String audit = OWNER.sql("""
                        SELECT coalesce(string_agg(coalesce(payload::text, '') || coalesce(reason, ''), '|'), '')
                          FROM audit.audit_event WHERE actor_id = :account""")
                .param("account", world.account.id()).query(String.class).single();
        assertThat(audit).doesNotContain(OWNER_PIN).doesNotContain("9999").doesNotContain("ownerPassword");
        String events = OWNER.sql("""
                        SELECT coalesce(string_agg(payload::text, '|'), '') FROM technical.outbox_event
                         WHERE aggregate_id IN (:booking, :reservation)""")
                .param("booking", created.bookingId()).param("reservation", created.reservationId())
                .query(String.class).single();
        assertThat(events).contains("VENUE_CANCELLED_CONFIRMED_BOOKING").doesNotContain("\"" + OWNER_PIN + "\"")
                .doesNotContain("ownerPassword");
        String records = OWNER.sql("""
                        SELECT coalesce(string_agg(row_to_json(r)::text, '|'), '')
                          FROM technical.idempotency_record r WHERE principal_id = :account""")
                .param("account", world.account.id()).query(String.class).single();
        assertThat(records).isNotEmpty().doesNotContain("\"" + OWNER_PIN + "\"").doesNotContain("ownerPassword");
    }

    /**
     * Category B / FRS section 2.11 (venue account DISABLED): the access token filter refuses a disabled account before
     * the route (401), so no PIN is checked or counted and nothing changes. The race of an account disabled between the
     * request and the command is the command's FORBIDDEN ({@code VenueConfirmedCancellationIntegrationTest}).
     */
    @Test
    void aDisabledVenueAccountIsUnauthorizedAndNothingIsChecked() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String token = venue(world);
        OWNER.sql("UPDATE iam.venue_account SET account_status = 'DISABLED' WHERE id = :id")
                .param("id", world.account.id()).update();

        mvc.perform(cancel(token, created, UUID.randomUUID().toString(), pin(OWNER_PIN)))
                .andExpect(status().isUnauthorized());
        assertThat(booking(created.bookingId()).status()).isEqualTo("CONFIRMED");
        assertThat(refusals(world.account.id())).isEmpty();
        assertThat(successAudit(created.bookingId())).isZero();
    }

    @Test
    void aManualBookingIsBookingStateChangedEvenWithTheRightPin() throws Exception {
        BookingFlows.Booking manual = flows.manualConfirmed();
        flows.ownerPin(manual.venueAccountId());

        mvc.perform(post(BOOKINGS + manual.bookingId() + "/cancel-confirmed")
                        .contentType(MediaType.APPLICATION_JSON).content(pin(OWNER_PIN))
                        .header("Authorization", "Bearer "
                                + accessTokens.issue(AuthSubjectType.VENUE, manual.venueAccountId()))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BOOKING_STATE_CHANGED"));
        assertThat(booking(manual.bookingId()).status()).isEqualTo("CONFIRMED");
    }

    // --------------------------------------------------------------------------------------- Phase 2: idempotency

    /** ADR-015: the replay writes nothing - no history, refund decision, audit row, event or second record. */
    @Test
    void aReplayWritesNothingTwice() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String key = UUID.randomUUID().toString();

        mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN))).andExpect(status().isOk());
        Writes first = writes(world, created, key);
        mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN))).andExpect(status().isOk());
        mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN))).andExpect(status().isOk());

        assertThat(writes(world, created, key)).isEqualTo(first).isEqualTo(new Writes(3, 1, 1, 1, 1, 1));
    }

    /**
     * PO decision Q3 (24 Oct 2026): the PIN is checked before the Idempotency-Key lookup, so a replay is checked like a
     * first request. Under a key whose request already succeeded, a wrong PIN is 423 OWNER_PASSWORD_INVALID - audited
     * and counted - never the stored answer or 422; five of them lock the account, and then even the right PIN is 429.
     * Nothing is cancelled or written twice. (Replaces the test that pinned the earlier replay order.)
     */
    @Test
    void q3UnderASuccessfulKeyAWrongPinIsCheckedCountedAndLocksTheAccountOut() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String key = UUID.randomUUID().toString();
        mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN))).andExpect(status().isOk());
        Writes first = writes(world, created, key);

        for (int i = 0; i < OwnerPasswordAttempts.MAX_FAILURES; i++) {
            mvc.perform(cancel(venue(world), created, key, pin(String.format("%04d", i))))
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"))
                    .andExpect(jsonPath("$.data").doesNotExist());
            assertThat(failures(world.account.id())).as("counted").isEqualTo(i + 1);
        }
        mvc.perform(cancel(venue(world), created, key, pin("9999")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(refusals(world.account.id())).hasSize(OwnerPasswordAttempts.MAX_FAILURES + 2)
                .filteredOn(row -> row.endsWith("true")).as("the two locked-out attempts").hasSize(2);
        assertThat(failures(world.account.id())).as("locked-out attempts are not counted again")
                .isEqualTo(OwnerPasswordAttempts.MAX_FAILURES);
        assertThat(accepted(world.account.id())).isEmpty();
        assertThat(writes(world, created, key)).as("nothing ran again").isEqualTo(first);
    }

    /**
     * Q3: after a wrong PIN under the key, the right one still replays the original answer - the stored body, nothing
     * run again; a missing PIN under the key is 423 OWNER_PASSWORD_REQUIRED, never the stored answer, and not counted.
     */
    @Test
    void q3UnderASuccessfulKeyTheRightPinReplaysTheOriginalAnswerAndAMissingOneIsRequired() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String key = UUID.randomUUID().toString();
        MvcResult original = mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN)))
                .andExpect(status().isOk()).andReturn();
        Writes first = writes(world, created, key);

        mvc.perform(cancel(venue(world), created, key, pin("9999"))).andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_INVALID"));
        for (String missing : List.of("{}", pin(""), pin("   "))) {
            mvc.perform(cancel(venue(world), created, key, missing)).andExpect(status().isLocked())
                    .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        MvcResult replay = mvc.perform(cancel(venue(world), created, key, pin(OWNER_PIN)))
                .andExpect(status().isOk()).andReturn();

        assertThat(body(replay).get("data")).isEqualTo(body(original).get("data"));
        assertThat(writes(world, created, key)).as("exactly once").isEqualTo(first);
        assertThat(failures(world.account.id())).as("only the wrong PIN counted").isOne();
        assertThat(accepted(world.account.id())).as("a replay is no refusal").isEmpty();
    }

    /**
     * Two identical requests (same key, same PIN) at once: both serialise on the booking's locks, taken by
     * {@code begin_command} before the idempotency record; the second then finds the first's completed record and
     * replays it. One cancellation, every write once.
     */
    @Test
    void twoIdenticalRequestsAtOnceCancelOnceAndBothAnswerTheSame() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String token = venue(world);
        String key = UUID.randomUUID().toString();

        ConcurrentRace.Result<String> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            MockHttpServletRequestBuilder request = cancel(token, created, key, pin(OWNER_PIN));
            start.await();
            MvcResult result = mvc.perform(request).andReturn();
            return result.getResponse().getStatus() + " " + body(result).path("data").path("status").asText();
        });

        assertThat(race.failures()).isEmpty();
        assertThat(race.values()).containsExactly("200 CANCELLED", "200 CANCELLED");
        assertThat(writes(world, created, key)).isEqualTo(new Writes(3, 1, 1, 1, 1, 1));
    }

    /** Two cancellations with different keys at once: one cancels, the other finds it CANCELLED (409). */
    @Test
    void twoCancellationsWithDifferentKeysAtOnceCancelOnce() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = confirmed(world);
        String token = venue(world);

        ConcurrentRace.Result<Integer> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            MockHttpServletRequestBuilder request = cancel(token, created, UUID.randomUUID().toString(),
                    pin(OWNER_PIN));
            start.await();
            return mvc.perform(request).andReturn().getResponse().getStatus();
        });

        assertThat(race.values()).containsExactlyInAnyOrder(200, 409);
        assertThat(history(created.bookingId())).hasSize(3);
        assertThat(successAudit(created.bookingId())).as("the refused one's success row rolled back").isOne();
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).hasSize(1);
        assertThat(VenueAnswerDb.count("loyalty.booking_fee_refund WHERE booking_id = :id", created.bookingId()))
                .isOne();
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** What one cancellation writes: history rows, fee decisions, SUCCESS audit rows, events, idempotency records. */
    private record Writes(int history, long refunds, long successAudit, int cancelledEvents, int releasedEvents,
                          long idempotencyRecords) {
    }

    private Writes writes(OnlineBookingWorld world, CreatedBooking created, String key) {
        return new Writes(history(created.bookingId()).size(),
                VenueAnswerDb.count("loyalty.booking_fee_refund WHERE booking_id = :id", created.bookingId()),
                successAudit(created.bookingId()),
                events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId()).size(),
                events(IntegrationEvents.RESERVATION_RELEASED, created.reservationId()).size(),
                VenueAnswerDb.idempotencyRecords(world.account.id(), key));
    }

    private static long successAudit(UUID bookingId) {
        return VenueAnswerDb.count("""
                audit.audit_event WHERE subject_id = :id AND action = 'VENUE_CANCELLED_CONFIRMED_BOOKING'
                   AND owner_password_outcome = 'SUCCESS'""", bookingId);
    }

    /** The gate's refusals of the account, as "OUTCOME protectedAction lockedOut", oldest first. */
    private static List<String> refusals(UUID accountId) {
        return OWNER.sql("""
                        SELECT owner_password_outcome || ' ' || (payload->>'protectedAction') || ' '
                               || (payload->>'lockedOut')
                          FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_REFUSED' AND subject_id = :id ORDER BY occurred_at""")
                .param("id", accountId).query(String.class).list();
    }

    /** The gate's acceptance rows of the account (PO decision Q2), as "OUTCOME protectedAction refusalCode". */
    private static List<String> accepted(UUID accountId) {
        return OWNER.sql("""
                        SELECT owner_password_outcome || ' ' || (payload->>'protectedAction') || ' '
                               || (payload->>'refusalCode')
                          FROM audit.audit_event
                         WHERE action = 'OWNER_PASSWORD_ACCEPTED' AND subject_id = :id ORDER BY occurred_at""")
                .param("id", accountId).query(String.class).list();
    }

    /** The account's counted wrong PINs: identity's OWNER_PASSWORD rate-limit counter (0 without a row). */
    private static long failures(UUID accountId) {
        return OWNER.sql("""
                        SELECT coalesce((SELECT counter FROM iam.security_rate_limit_counter
                                          WHERE limiter_type = 'OWNER_PASSWORD'
                                            AND subject_key_hash = encode(sha256(convert_to(
                                                    'venue-account:' || CAST(:id AS text), 'UTF8')), 'hex')), 0)""")
                .param("id", accountId).query(Long.class).single();
    }

    private CreatedBooking confirmed(OnlineBookingWorld world) {
        CreatedBooking created = creation.create(world.request(world.anotherCustomer(), world.room, world.start(), 60));
        assertThat(responses.accept(world.account.id(), created.bookingId())).isInstanceOf(CommandAnswer.Ok.class);
        flows.ownerPin(world.account.id());
        return created;
    }

    private String venue(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.VENUE, world.account.id());
    }

    private static String pin(String value) {
        return "{\"ownerPassword\": \"" + value + "\"}";
    }

    private static MockHttpServletRequestBuilder cancel(String token, CreatedBooking created, String key, String body) {
        MockHttpServletRequestBuilder request = post(BOOKINGS + created.bookingId() + "/cancel-confirmed")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + token);
        if (key != null) {
            request.header(OpenApiConfig.IDEMPOTENCY_KEY, key);
        }
        return request;
    }

    private JsonNode body(MvcResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
