package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.venue.api.VenueContacts;

/**
 * E7-46 joined to E7-42 - a failure after every business write rolls back the Idempotency-Key record and both outbox
 * events with the booking, its reservation, history and job (arch 08 section 6, ADR-015), so the same key may be
 * retried and then creates the booking once.
 */
class OnlineBookingIdempotencyIntegrationTest extends IntegrationTest {

    static final String ENDPOINT = "POST /api/v1/customer/bookings";

    @Autowired
    CommandRunner runner;

    @Autowired
    Idempotency idempotency;

    @Autowired
    OnlineBookingCreation creation;

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

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    /** A stored response without the phone, so no projection is needed for this test's own wrap. */
    public record BookingRef(UUID bookingId) {
    }

    @Test
    void aFailureAfterEveryWriteRollsBackTheKeyRecordAndBothEventsAndTheKeyMayBeRetried() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingRequest request = world.request(world.start(), 120);
        IdempotentRequest key = idempotency.request(new AuthenticatedSubject(AuthSubjectType.CUSTOMER,
                world.customer.id()), ENDPOINT, UUID.randomUUID(), Map.of(), request);
        StoredResponse<BookingRef> response = StoredResponse.of(BookingRef.class, 201);
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
        CreateOnlineBookingCommand command = new CreateOnlineBookingCommand(request, world.entry(world.room),
                predicate, store, ledger, failing, outbox, venues);

        assertThatThrownBy(() -> runner.run(idempotency.wrap(key, response, refOf(command))))
                .isInstanceOf(IllegalStateException.class);

        UUID failed = bookingId.get();
        assertThat(failed).isNotNull();
        assertThat(count("booking.booking WHERE id = :id", failed)).isZero();
        assertThat(count("inventory.resource_reservation WHERE booking_id = :id", failed)).isZero();
        assertThat(count("technical.outbox_event WHERE aggregate_id = :id", failed)).isZero();
        assertThat(count("technical.outbox_event WHERE payload->>'resourceId' = CAST(:id AS text)", world.room.id()))
                .as("no ReservationHeld either").isZero();
        assertThat(count("technical.idempotency_record WHERE principal_id = :id", world.customer.id())).isZero();

        IdempotentResult<BookingRef> retried = creation.create(request, key, response,
                created -> new BookingRef(created.bookingId()));

        assertThat(retried.replayed()).isFalse();
        assertThat(retried.status()).isEqualTo(201);
        assertThat(count("booking.booking WHERE id = :id", retried.body().bookingId())).isEqualTo(1);
        assertThat(count("technical.outbox_event WHERE aggregate_id = :id", retried.body().bookingId()))
                .as("BookingCreated").isEqualTo(1);
        assertThat(count("technical.idempotency_record WHERE principal_id = :id", world.customer.id())).isEqualTo(1);

        IdempotentResult<BookingRef> replay = creation.create(request, key, response,
                created -> new BookingRef(created.bookingId()));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(retried.body());
        assertThat(count("booking.booking WHERE customer_id = :id", world.customer.id())).isEqualTo(1);
    }

    private static Command<BookingRef> refOf(CreateOnlineBookingCommand command) {
        return new Command<>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return command.eligibilityCategory();
            }

            @Override
            public CommandScope scope() {
                return command.scope();
            }

            @Override
            public BookingRef execute(CommandContext context) {
                return new BookingRef(command.execute(context).bookingId());
            }
        };
    }

    private static long count(String fromWhere, UUID id) {
        return OWNER.sql("SELECT count(*) FROM " + fromWhere).param("id", id).query(Long.class).single();
    }
}
