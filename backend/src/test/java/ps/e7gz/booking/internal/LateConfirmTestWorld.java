package ps.e7gz.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import ps.e7gz.common.command.CommandRunner;

/**
 * TEST-ONLY bridge for E7-58 tests outside this package (the Late Confirm boundary tests sit next to
 * {@code booking.lateconfirm.internal}): a real venue, room and verified customer, real expired bookings made by the
 * real creation command and T1, and committed reads - as plain public values, so this package's fixtures stay
 * package-private.
 */
public final class LateConfirmTestWorld {

    private final OnlineBookingWorld world;
    private final LateConfirmDb db;
    private final OnlineBookingCreation creation;

    private LateConfirmTestWorld(OnlineBookingWorld world, LateConfirmDb db, OnlineBookingCreation creation) {
        this.world = world;
        this.db = db;
        this.creation = creation;
    }

    /** A new venue (open now, from two hours ago to ten hours ahead), its account, a PS5 room and a customer. */
    public static LateConfirmTestWorld create(ApplicationContext context) {
        Objects.requireNonNull(context, "context");
        OnlineBookingCreation creation = context.getBean(OnlineBookingCreation.class);
        LateConfirmDb db = new LateConfirmDb(context.getBean(CommandRunner.class), creation,
                context.getBean(VenueAnswerStore.class), context.getBean(VenueAnswers.class));
        return new LateConfirmTestWorld(OnlineBookingWorld.create(), db, creation);
    }

    public UUID venueAccountId() {
        return world.account.id();
    }

    public UUID customerId() {
        return world.customer.id();
    }

    /** A whole minute about an hour from now, inside the session and past the lead time. */
    public Instant start() {
        return world.start();
    }

    /** A real Online Booking of the customer, expired by T1: VENUE_RESPONSE_EXPIRED. */
    public UUID expired(Instant start, int minutes) {
        return db.expired(world, start, minutes);
    }

    /** A real PENDING Online Booking of the customer: one counted commitment. */
    public UUID pending(Instant start, int minutes) {
        return creation.create(world.request(start, minutes)).bookingId();
    }

    /** The customer's counted commitments now. */
    public long commitments() {
        return VenueAnswerDb.count("booking.booking WHERE customer_id = :id AND commitment_period IS NOT NULL",
                world.customer.id());
    }

    // ---------------------------------------------------------------- committed reads of one booking

    public record Attempt(UUID id, Instant initiatedAt, Instant expiresAt, String response, Instant respondedAt) {
    }

    public static Attempt attempt(UUID bookingId) {
        LateConfirmDb.Attempt found = LateConfirmDb.attempt(bookingId).orElseThrow();
        return new Attempt(found.id(), found.initiatedAt(), found.expiresAt(), found.response(), found.respondedAt());
    }

    public static long attempts(UUID bookingId) {
        return LateConfirmDb.attempts(bookingId);
    }

    public static String status(UUID bookingId) {
        return VenueAnswerDb.booking(bookingId).status();
    }

    public static String terminalReason(UUID bookingId) {
        return VenueAnswerDb.booking(bookingId).terminalReason();
    }

    public static Instant terminalAt(UUID bookingId) {
        return VenueAnswerDb.booking(bookingId).terminalAt();
    }

    /** Whether any LATE_CONFIRM_HOLD of the booking was ever written. */
    public static boolean anyHold(UUID bookingId) {
        return LateConfirmDb.reservations(bookingId).stream().anyMatch(r -> r.type().equals("LATE_CONFIRM_HOLD"));
    }

    /** The Booking Event types of the booking's history, oldest first. */
    public static List<String> bookingEvents(UUID bookingId) {
        return LateConfirmDb.history(bookingId).stream().map(LateConfirmDb.Event::eventType)
                .filter(Objects::nonNull).toList();
    }

    /** The {@code warning_sent_at} of the booking's latest attempt, or null. */
    public static Instant warningSentAt(UUID bookingId) {
        return LateConfirmDb.warningSentAt(bookingId);
    }

    /** How many outbox events of this type the booking has. */
    public static int events(String type, UUID bookingId) {
        return VenueAnswerDb.events(type, bookingId).size();
    }

    /** See {@link LateConfirmDb#deadlineAt}. */
    public static void deadlineAt(UUID bookingId, Instant expiresAt) {
        LateConfirmDb.deadlineAt(bookingId, expiresAt);
    }

    /**
     * Shortens the open attempt's deadline to a millisecond after its initiation and waits until the database clock is
     * more than a second past it: a test can then store any deadline from {@code now - 1 s} on, moving it only later.
     */
    public static void deadlineJustAfterInitiation(UUID bookingId) {
        Instant initiated = attempt(bookingId).initiatedAt();
        LateConfirmDb.deadlineAt(bookingId, initiated.plusMillis(1));
        ps.e7gz.support.boundary.Deadlines.awaitDatabaseClockPast(initiated.plus(Duration.ofMillis(1_200)));
    }
}
