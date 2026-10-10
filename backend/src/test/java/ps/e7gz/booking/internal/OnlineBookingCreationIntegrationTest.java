package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.OnlineCreationPredicate.Approval;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.common.money.Money;
import ps.e7gz.config.BookingFeeConfiguration;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.SlotTaken;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.trust.BanFixtures;
import ps.e7gz.venue.api.SessionSnapshots;
import ps.e7gz.venue.api.VenueContacts;

/**
 * E7-46 - online booking creation (O1) against the migrated database: the twenty conditions with real owner reads, the
 * PENDING Booking with its snapshots, BOOKING reservation, first history row and T1 job in one command transaction,
 * the database as the final authority under concurrency, and the quote computing exactly what creation stores.
 *
 * <p>The {@code BookingCreated} / {@code ReservationHeld} events (E7-41) and the Idempotency-Key record and replay
 * (E7-42) are tested in {@code CustomerBookingCreateHttpTest} and {@code OnlineBookingIdempotencyIntegrationTest}.
 */
class OnlineBookingCreationIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    BookingQuotes quotes;

    @Autowired
    OnlineCreationPredicate predicate;

    @Autowired
    OnlineBookingStore store;

    @Autowired
    ReservationLedger ledger;

    @Autowired
    ScheduledJobs jobs;

    @Autowired
    Outbox outbox;

    @Autowired
    VenueContacts venues;

    @Autowired
    SessionSnapshots sessionSnapshots;

    private final UUID correlationId = UUID.randomUUID();
    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(correlationId);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------- creation and persistence

    @Test
    void aBookingIsCreatedPendingWithItsSnapshotsReservationHistoryAndDeadlineJob() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();

        CreatedBooking created = creation.create(world.request(start, 90));

        Map<String, Object> row = OWNER.sql("SELECT * FROM booking.booking WHERE id = :id")
                .param("id", created.bookingId()).query().singleRow();
        assertThat(row).containsEntry("status", "PENDING").containsEntry("source", "ONLINE")
                .containsEntry("customer_id", world.customer.id()).containsEntry("venue_id", world.venue.id())
                .containsEntry("resource_id", world.room.id()).containsEntry("activity", "PLAYSTATION")
                .containsEntry("console_type", "PS5").containsEntry("play_mode", "SINGLE")
                .containsEntry("resource_name_snapshot", world.room.name())
                .containsEntry("nominal_booked_minutes", 90);
        assertThat(instant(row.get("start_at"))).isEqualTo(start);
        assertThat(instant(row.get("end_at"))).isEqualTo(start.plus(Duration.ofMinutes(90)));
        assertThat(row.get("terminal_reason")).isNull();
        assertThat(row.get("fee_treatment")).isNull();
        assertThat(row.get("commitment_period")).isNotNull();

        // FR-BOK-06: 77.77 × 90 ÷ 60 = 116.655 → 116.66 (half-up, once); the fee is the launch EGP 0.00
        assertAmount(row.get("hourly_price_snapshot"), "77.77");
        assertAmount(row.get("venue_price_total"), "116.66");
        assertAmount(row.get("booking_fee_snapshot"), "0.00");
        assertAmount(row.get("total_amount"), "116.66");
        assertThat(created.amounts()).isEqualTo(new BookingAmounts(Money.of("77.77"), 90, Money.of("116.66"),
                Money.of("0.00"), Money.of("116.66")));
        assertThat(created.amounts().venuePriceTotal().amount().scale()).isEqualTo(2);
        assertThat(created.amounts().totalAmount().amount().scale()).isEqualTo(2);

        // the Operating Session snapshot; its operating day is the business date (FR-AVL-14)
        Map<String, Object> session = OWNER.sql("""
                        SELECT id, operating_day, local_start, local_end, timezone FROM venue.operating_session
                         WHERE venue_id = :venue""").param("venue", world.venue.id()).query().singleRow();
        assertThat(row.get("operating_session_id")).isEqualTo(session.get("id"));
        assertThat(row.get("operating_day")).isEqualTo(session.get("operating_day"));
        assertThat(row.get("business_date")).isEqualTo(session.get("operating_day"));
        assertThat(row.get("session_local_start")).isEqualTo(session.get("local_start"));
        assertThat(row.get("session_local_end")).isEqualTo(session.get("local_end"));
        assertThat(row.get("session_timezone")).isEqualTo("Africa/Cairo");

        // FR-BOK-13: the deadline is the command instant + 10 minutes, exactly, and created_at is that instant
        Instant createdAt = instant(row.get("created_at"));
        assertThat(created.createdAt()).isEqualTo(createdAt);
        assertThat(instant(row.get("venue_response_expires_at"))).isEqualTo(createdAt.plus(Duration.ofMinutes(10)))
                .isEqualTo(created.venueResponseExpiresAt());

        Map<String, Object> reservation = OWNER.sql("SELECT * FROM inventory.resource_reservation WHERE booking_id = :id")
                .param("id", created.bookingId()).query().singleRow();
        assertThat(reservation).containsEntry("id", created.reservationId()).containsEntry("reservation_type", "BOOKING")
                .containsEntry("resource_id", world.room.id()).containsEntry("active_flag", true);
        assertThat(instant(reservation.get("planned_start"))).isEqualTo(start);
        assertThat(instant(reservation.get("planned_end"))).isEqualTo(start.plus(Duration.ofMinutes(90)));
        assertThat(instant(reservation.get("actual_reservation_start"))).isEqualTo(createdAt);

        List<Map<String, Object>> history = OWNER.sql("SELECT * FROM booking.booking_history WHERE booking_id = :id")
                .param("id", created.bookingId()).query().listOfRows();
        assertThat(history).singleElement().satisfies(entry -> {
            assertThat(entry).containsEntry("sequence", 1L).containsEntry("entry_kind", "STATUS_TRANSITION")
                    .containsEntry("to_status", "PENDING").containsEntry("actor_type", "CUSTOMER")
                    .containsEntry("actor_id", world.customer.id()).containsEntry("correlation_id", correlationId);
            assertThat(entry.get("from_status")).isNull();
            assertThat(instant(entry.get("occurred_at"))).isEqualTo(createdAt);
        });

        Map<String, Object> job = OWNER.sql("SELECT * FROM technical.scheduled_job WHERE idempotency_key = :key")
                .param("key", "VENUE_RESPONSE_EXPIRY:" + created.bookingId()).query().singleRow();
        assertThat(job).containsEntry("type", "VENUE_RESPONSE_EXPIRY").containsEntry("status", "PENDING")
                .containsEntry("subject_type", "BOOKING").containsEntry("subject_id", created.bookingId())
                .containsEntry("correlation_id", correlationId);
        assertThat(instant(job.get("due_at"))).isEqualTo(created.venueResponseExpiresAt());
    }

    @Test
    void aFailureAfterTheWritesRollsBackTheBookingReservationHistoryAndJob() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        AtomicReference<UUID> bookingId = new AtomicReference<>();
        ScheduledJobs failing = new ScheduledJobs() {
            @Override
            public boolean schedule(CommandContext context, NewJob job) {
                jobs.schedule(context, job);
                bookingId.set(job.subjectId());
                throw new IllegalStateException("fail after every write");
            }

            @Override
            public void rearm(CommandContext context, String idempotencyKey, Instant dueAt) {
                throw new UnsupportedOperationException();
            }
        };
        OnlineBookingRequest request = world.request(world.start(), 120);

        assertThatThrownBy(() -> runner.run(new CreateOnlineBookingCommand(request, world.entry(world.room), predicate,
                store, ledger, failing, outbox, venues))).isInstanceOf(IllegalStateException.class);

        UUID id = bookingId.get();
        assertThat(id).isNotNull();
        assertThat(count("booking.booking WHERE id = :id", id)).isZero();
        assertThat(count("inventory.resource_reservation WHERE booking_id = :id", id)).isZero();
        assertThat(count("booking.booking_history WHERE booking_id = :id", id)).isZero();
        assertThat(count("technical.scheduled_job WHERE subject_id = :id", id)).isZero();
        // and the slot is still free for the next request
        assertThat(creation.create(request).startAt()).isEqualTo(request.startAt());
    }

    @Test
    void aRefusedRequestWritesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().unpriced().create();

        assertRefused(() -> creation.create(world.request(world.start(), 120)),
                OnlineCreationCondition.OTHER_PRODUCT_VALIDATION);

        assertThat(count("booking.booking WHERE customer_id = :id", world.customer.id())).isZero();
        assertThat(count("inventory.resource_reservation WHERE resource_id = :id", world.room.id())).isZero();
    }

    @Test
    void anUnknownResourceIsNotFoundBeforeAnyCommandLocksIt() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingRequest unknown = new OnlineBookingRequest(world.customer.id(), UUID.randomUUID(),
                PlayMode.SINGLE, world.start(), 120);

        assertThatThrownBy(() -> creation.create(unknown)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> quotes.quote(UUID.randomUUID(), PlayMode.SINGLE, 120))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }

    // ---------------------------------------------------------------- FR-BOK-02 lead time, to the command instant

    @Test
    void aStartExactlyFifteenMinutesAfterTheCommandInstantIsAccepted() {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        CreatedBooking created = createAtCommandInstantPlus(world, Duration.ofMinutes(15));

        assertThat(created.startAt()).isEqualTo(created.createdAt().plus(Duration.ofMinutes(15)));
    }

    @Test
    void aStartFourteenMinutesFiftyNineSecondsAfterTheCommandInstantIsRefused() {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        assertRefused(() -> createAtCommandInstantPlus(world, Duration.ofMinutes(14).plusSeconds(59)),
                OnlineCreationCondition.LEAD_TIME);
    }

    // ---------------------------------------------------------------- FR-BOK-09 intake window

    @Test
    void elevenMinutesBeforeTheCurrentSessionEndsARequestForTheNextSessionIsAccepted() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().withoutSession().create();
        world.session(world.t0.minus(Duration.ofHours(6)), world.t0.plus(Duration.ofMinutes(11)), 0);
        world.session(world.t0.plus(Duration.ofHours(1)), world.t0.plus(Duration.ofHours(9)), 1);

        CreatedBooking created = creation.create(world.request(world.t0.plus(Duration.ofHours(2)), 120));

        assertThat(created.startAt()).isEqualTo(world.t0.plus(Duration.ofHours(2)));
    }

    @Test
    void nineMinutesBeforeTheCurrentSessionEndsTheIntakeWindowIsClosed() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().withoutSession().create();
        world.session(world.t0.minus(Duration.ofHours(6)), world.t0.plus(Duration.ofMinutes(9)), 0);
        world.session(world.t0.plus(Duration.ofHours(1)), world.t0.plus(Duration.ofHours(9)), 1);

        assertRefused(() -> creation.create(world.request(world.t0.plus(Duration.ofHours(2)), 120)),
                OnlineCreationCondition.BEFORE_INTAKE_CUTOFF);
    }

    // ---------------------------------------------------------------- each condition failing alone, real reads

    @Test
    void condition1AnUnverifiedCustomer() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().unverifiedCustomer().create();

        assertRefused(() -> creation.create(world.request(world.start(), 120)), OnlineCreationCondition.PHONE_VERIFIED);
    }

    @Test
    void condition2ABannedCustomer() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        BanFixtures.activeBan(world.customer.id());

        assertRefused(() -> creation.create(world.request(world.start(), 120)), OnlineCreationCondition.NOT_BANNED);
    }

    @Test
    void condition3AFifthCommitmentIsRefusedAndFourAreAllowed() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        for (int i = 0; i < 4; i++) {
            creation.create(world.request(start.plus(Duration.ofHours(2L * i)), 60));
        }

        assertRefused(() -> creation.create(world.request(start.plus(Duration.ofHours(8)), 60)),
                OnlineCreationCondition.BELOW_COMMITMENT_LIMIT);
    }

    @Test
    void condition4AnOverlappingCommitmentOnAnotherRoomIsRefusedButBackToBackIsNot() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        creation.create(world.request(start, 120));

        assertRefused(() -> creation.create(world.request(world.customer.id(), second,
                start.plus(Duration.ofMinutes(60)), 120)), OnlineCreationCondition.NO_OVERLAPPING_COMMITMENT);
        assertThat(creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofMinutes(120)), 60)))
                .isNotNull();
    }

    @Test
    void condition5AVenueThatIsNotMarketplaceActive() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().status(MarketplaceStatus.INACTIVE).create();

        assertRefused(() -> creation.create(world.request(world.start(), 120)),
                OnlineCreationCondition.VENUE_MARKETPLACE_ACTIVE);
    }

    @Test
    void condition6AVenueWhoseAccountIsDisabled() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().accountDisabled().create();

        assertRefused(() -> creation.create(world.request(world.start(), 120)),
                OnlineCreationCondition.VENUE_ACCOUNT_ENABLED);
    }

    @Test
    void condition8AVenueThatIsNotOpenNow() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().withoutSession().create();
        world.session(world.t0.plus(Duration.ofHours(1)), world.t0.plus(Duration.ofHours(9)), 0);

        assertRefused(() -> creation.create(world.request(world.t0.plus(Duration.ofHours(2)), 120)),
                OnlineCreationCondition.VENUE_CURRENTLY_OPEN);
    }

    @Test
    void condition10AVenueSellingBlock() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OWNER.sql("""
                        INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at)
                        VALUES (gen_random_uuid(), :venue, 'E7-46 block', true, now() - interval '1 hour')""")
                .param("venue", world.venue.id()).update();

        assertRefused(() -> creation.create(world.request(world.start(), 120)),
                OnlineCreationCondition.NO_VENUE_SELLING_BLOCK);
    }

    @Test
    void condition11AResourceSellingBlock() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OWNER.sql("""
                        INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at)
                        VALUES (gen_random_uuid(), :room, 'E7-46 block', true, now() - interval '1 hour')""")
                .param("room", world.room.id()).update();

        assertRefused(() -> creation.create(world.request(world.start(), 120)),
                OnlineCreationCondition.NO_RESOURCE_SELLING_BLOCK);
    }

    @Test
    void conditions13And14AnInvalidDuration() {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        assertRefused(() -> creation.create(world.request(world.start(), 30)), OnlineCreationCondition.DURATION_RANGE);
        assertRefused(() -> creation.create(world.request(world.start(), 390)), OnlineCreationCondition.DURATION_RANGE);
        assertRefused(() -> creation.create(world.request(world.start(), 75)),
                OnlineCreationCondition.DURATION_INCREMENT);
    }

    @Test
    void condition15AnIntervalRunningPastTheSessionEnd() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant sessionEnd = world.t0.plus(Duration.ofHours(10)).truncatedTo(ChronoUnit.MINUTES);

        assertRefused(() -> creation.create(world.request(sessionEnd.minus(Duration.ofMinutes(60)), 90)),
                OnlineCreationCondition.WITHIN_ONE_SESSION);
        assertThat(creation.create(world.request(sessionEnd.minus(Duration.ofMinutes(60)), 60))).isNotNull();
    }

    @Test
    void condition16ADisabledRoom() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().room(room -> room.disabled()).create();

        assertRefused(() -> creation.create(world.request(world.start(), 120)), OnlineCreationCondition.RESOURCE_ACTIVE);
    }

    @Test
    void condition17ARoomUnderOwnerReview() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().room(room -> room.ownerReviewRequired()).create();

        assertRefused(() -> creation.create(world.request(world.start(), 120)),
                OnlineCreationCondition.RESOURCE_NOT_UNDER_OWNER_REVIEW);
    }

    @Test
    void condition18ASlotAnotherCustomerHoldsAnswersSlotNoLongerAvailable() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        creation.create(world.request(world.anotherCustomer(), world.room, start, 120));

        assertRefused(() -> creation.create(world.request(start.plus(Duration.ofMinutes(60)), 120)),
                OnlineCreationCondition.NO_BLOCKING_RESERVATION);
        // back to back is not an overlap
        assertThat(creation.create(world.request(start.plus(Duration.ofMinutes(120)), 60))).isNotNull();
    }

    @Test
    void condition18MaintenanceAndAClosureAreBlockingReservations() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        OWNER.sql("""
                        INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
                        VALUES (gen_random_uuid(), :room, tstzrange(:start, :end, '[)'), 'E7-46 maintenance', false)""")
                .param("room", world.room.id()).param("start", utc(start))
                .param("end", utc(start.plus(Duration.ofMinutes(60)))).update();
        OWNER.sql("""
                        INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
                        VALUES (gen_random_uuid(), :venue, tstzrange(:start, :end, '[)'), 'PARTIAL', 'E7-46 closure')""")
                .param("venue", world.venue.id()).param("start", utc(start.plus(Duration.ofHours(3))))
                .param("end", utc(start.plus(Duration.ofHours(4)))).update();

        assertRefused(() -> creation.create(world.request(start, 120)), OnlineCreationCondition.NO_BLOCKING_RESERVATION);
        assertRefused(() -> creation.create(world.request(start.plus(Duration.ofHours(2)), 120)),
                OnlineCreationCondition.NO_BLOCKING_RESERVATION);
    }

    @Test
    void condition19APlayStationRoomWithoutAPlayMode() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingRequest noMode = new OnlineBookingRequest(world.customer.id(), world.room.id(), null,
                world.start(), 120);

        assertRefused(() -> creation.create(noMode), OnlineCreationCondition.ACTIVITY_COMPATIBLE);
    }

    @Test
    void condition20AVenueWithoutAPriceAnswersResourceNotEligible() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().unpriced().create();

        assertThatThrownBy(() -> creation.create(world.request(world.start(), 120)))
                .isInstanceOfSatisfying(CreationRefused.class, refused -> {
                    assertThat(refused.condition()).isEqualTo(OnlineCreationCondition.OTHER_PRODUCT_VALIDATION);
                    assertThat(refused.code()).isEqualTo(ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
                });
        assertThatThrownBy(() -> quotes.quote(world.room.id(), PlayMode.SINGLE, 120))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.RESOURCE_NOT_ELIGIBLE));
    }

    // ---------------------------------------------------------------- the database is the final authority

    @Test
    void theDatabaseRefusesATakenSlotEvenWhenEveryConditionWasSkippedAndKeepsNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        creation.create(world.request(world.anotherCustomer(), world.room, start, 120));
        OnlineBookingRequest request = world.request(start.plus(Duration.ofMinutes(30)), 60);

        assertThatThrownBy(() -> runner.run(new CreateOnlineBookingCommand(request, world.entry(world.room),
                approvingEverything(world, request), store, ledger, jobs, outbox, venues)))
                .isInstanceOf(SlotTaken.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE));
        assertThat(count("booking.booking WHERE customer_id = :id", world.customer.id())).isZero();
    }

    @Test
    void theDatabaseRefusesAnOverlappingCommitmentEvenWhenEveryConditionWasSkipped() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        creation.create(world.request(start, 120));
        OnlineBookingRequest request = world.request(world.customer.id(), second, start.plus(Duration.ofMinutes(30)), 60);

        assertThatThrownBy(() -> runner.run(new CreateOnlineBookingCommand(request, world.entry(second),
                approvingEverything(world, request), store, ledger, jobs, outbox, venues)))
                .satisfies(e -> assertThat(DatabaseErrorCodes.translate(e)).contains(ApiErrorCode.OVERLAPPING_COMMITMENT));
        assertThat(count("booking.booking WHERE resource_id = :id", second.id())).isZero();
    }

    @Test
    void concurrentRequestsForOneSlotCreateExactlyOneBooking() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        List<UUID> customers = List.of(world.customer.id(), world.anotherCustomer(), world.anotherCustomer(),
                world.anotherCustomer());

        List<Object> outcomes = race(customers.stream()
                .map(customer -> (Callable<Object>) () -> creation.create(world.request(customer, world.room, start, 120)))
                .toList());

        assertThat(outcomes).filteredOn(CreatedBooking.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).hasSize(3)
                .allSatisfy(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE));
        assertThat(count("inventory.resource_reservation WHERE resource_id = :id AND active_flag", world.room.id()))
                .isEqualTo(1);
    }

    @Test
    void concurrentRequestsOfOneCustomerForOverlappingRoomsCreateExactlyOneCommitment() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();

        List<Object> outcomes = race(List.of(
                () -> creation.create(world.request(world.customer.id(), world.room, start, 120)),
                () -> creation.create(world.request(world.customer.id(), second, start, 120))));

        assertThat(outcomes).filteredOn(CreatedBooking.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiErrorCode.OVERLAPPING_COMMITMENT));
    }

    // ---------------------------------------------------------------- quote / create parity

    @Test
    void theQuoteEqualsTheAmountsOfABookingCreatedWithTheSameInput() {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        BookingAmounts quoted = quotes.quote(world.room.id(), PlayMode.SINGLE, 150);
        CreatedBooking created = creation.create(world.request(world.start(), 150));

        assertThat(quoted).isEqualTo(created.amounts());
        assertThat(quoted.bookingFee().isZero()).isTrue();
        assertThat(count("booking.booking WHERE resource_id = :id", world.room.id())).as("the quote created nothing")
                .isEqualTo(1);
    }

    @Test
    void theQuoteAndTheBookingCarryTheSameNonZeroFeeTheDatabaseAccepts() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        BookingFeeConfiguration.change(runner, "12.5");
        try {
            BookingAmounts quoted = quotes.quote(world.room.id(), PlayMode.SINGLE, 90);
            CreatedBooking created = creation.create(world.request(world.start(), 90));

            assertThat(quoted).isEqualTo(created.amounts());
            assertThat(created.amounts()).isEqualTo(new BookingAmounts(Money.of("77.77"), 90, Money.of("116.66"),
                    Money.of("12.50"), Money.of("129.16")));
            Map<String, Object> row = OWNER.sql("SELECT booking_fee_snapshot, total_amount FROM booking.booking "
                    + "WHERE id = :id").param("id", created.bookingId()).query().singleRow();
            assertAmount(row.get("booking_fee_snapshot"), "12.50");
            assertAmount(row.get("total_amount"), "129.16");
        } finally {
            BookingFeeConfiguration.change(runner, "0");
        }
    }

    @Test
    void theQuoteHoldsNothingAndChecksNoAvailability() {
        OnlineBookingWorld world = OnlineBookingWorld.builder().room(room -> room.disabled()).create();

        assertThat(quotes.quote(world.room.id(), PlayMode.SINGLE, 60).venuePriceTotal()).isEqualTo(Money.of("77.77"));
        assertThat(count("inventory.resource_reservation WHERE resource_id = :id", world.room.id())).isZero();
        assertThatThrownBy(() -> quotes.quote(world.room.id(), PlayMode.SINGLE, 45))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_DURATION_INVALID));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs the creation command body inside a command whose request starts {@code lead} after that command's own
     * instant - the exact boundary of FR-BOK-02, which a request built before the command cannot hit.
     */
    private CreatedBooking createAtCommandInstantPlus(OnlineBookingWorld world, Duration lead) {
        OnlineBookingRequest template = world.request(world.start(), 120);
        CreateOnlineBookingCommand shape = new CreateOnlineBookingCommand(template, world.entry(world.room), predicate,
                store, ledger, jobs, outbox, venues);
        return runner.run(new Command<CreatedBooking>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return shape.eligibilityCategory();
            }

            @Override
            public CommandScope scope() {
                return shape.scope();
            }

            @Override
            public CreatedBooking execute(CommandContext context) {
                OnlineBookingRequest request = world.request(context.commandNow().plus(lead), 120);
                return new CreateOnlineBookingCommand(request, world.entry(world.room), predicate, store, ledger, jobs,
                        outbox, venues).execute(context);
            }
        });
    }

    /** A predicate that approves without checking anything, so only the database can refuse. */
    private OnlineCreationPredicate approvingEverything(OnlineBookingWorld world, OnlineBookingRequest request) {
        Instant end = request.startAt().plus(Duration.ofMinutes(request.durationMinutes()));
        SessionSnapshots.Session session = runner.run(new ReadCommand<>(ctx ->
                sessionSnapshots.containing(ctx, world.venue.id(), request.startAt()).orElseThrow()));
        OnlineCreationPredicate approving = mock(OnlineCreationPredicate.class);
        when(approving.check(any(), any(), any())).thenReturn(new Approval(end, session,
                BookingPricing.amounts(Money.of(OnlineBookingWorld.HOURLY), request.durationMinutes(), Money.ZERO),
                OWNER.sql("SELECT name FROM resource.resource WHERE id = :id").param("id", request.resourceId())
                        .query(String.class).single()));
        return approving;
    }

    record ReadCommand<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    /** Starts every task at once; each outcome is the task's result or the exception it threw. */
    private List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try (CorrelationId.Scope ignored = CorrelationId.open(UUID.randomUUID())) {
                        return task.call();
                    } catch (ApiException refused) {
                        return refused;
                    }
                }));
            }
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertRefused(ThrowingCallable call,
                                      OnlineCreationCondition condition) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CreationRefused.class, refused -> {
            assertThat(refused.condition()).isEqualTo(condition);
            assertThat(refused.code()).isEqualTo(condition.code());
        });
    }

    private static void assertAmount(Object column, String expected) {
        BigDecimal amount = (BigDecimal) column;
        assertThat(amount).isEqualTo(new BigDecimal(expected));
        assertThat(amount.scale()).isEqualTo(2);
    }

    private static long count(String fromWhere, UUID id) {
        return OWNER.sql("SELECT count(*) FROM " + fromWhere).param("id", id).query(Long.class).single();
    }

    private static Instant instant(Object column) {
        if (column instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return ((OffsetDateTime) column).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
