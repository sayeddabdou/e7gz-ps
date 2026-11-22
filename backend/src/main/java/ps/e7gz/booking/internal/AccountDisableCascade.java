package ps.e7gz.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmClosures;
import ps.e7gz.booking.api.VenueResponseExpiredResolutions;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingEventType;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.ResponseTimerPauses;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.venue.api.AccountDisableBookings;

/**
 * The Booking side of a Venue Account disable (O10; arch 06 section 2.8, FR-VEN-09, FR-VEN-10; E7-78), in the disable
 * command - the same steps as the Ban cascade ({@link BanCascadeCancellation}) for the two pre-confirmation states:
 * <ul>
 *   <li>PENDING -> VENUE_RESPONSE_EXPIRED at the command instant, non-terminal (FR-BOK-12): an emergency-paused
 *       response timer's pause ended first (FR-OPS-37), the BOOKING reservation released, the transition and the
 *       Booking Event {@code VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE} (the customer app's cause, FR-NTF-06), T1
 *       withdrawn and T6 scheduled at {@code startAt} (E7-118; PO decision E3, 19 Nov 2026). Its
 *       {@code VenueResponseExpired} says {@code interruptedByAccountDisable}, so no customer notification is created
 *       for it (PO decision E1, 19 Nov 2026: the ordinary one blames the venue, FR-NTF-06, FR-VEN-11);</li>
 *   <li>AWAITING_CUSTOMER_CONFIRMATION -> CANCELLED {@code VENUE_ACCOUNT_DISABLED}: its Late Confirmation closed and
 *       hold released through {@link LateConfirmClosures} (a paused window's pause ends there), its Booking Fee decision
 *       (the database's full refund, inside the app: to the Wallet or as Points, PO 19 Nov 2026) and one
 *       {@code BookingCancelled}, whose consumer sends {@code BOOKING_CANCELLED_VENUE_ACCOUNT_DISABLED}.</li>
 * </ul>
 * History actor SYSTEM, as every system consequence of another actor's command. A booking in any other state, of
 * another venue or a Manual Booking is a data defect: {@link IllegalStateException}, the whole disable rolls back.
 */
@Component
class AccountDisableCascade implements AccountDisableBookings {

    private static final Logger log = LoggerFactory.getLogger(AccountDisableCascade.class);
    static final BookingTerminalReason REASON = BookingTerminalReason.VENUE_ACCOUNT_DISABLED;

    /** FR-VEN-09: what a disable reaches; ACTIVE only while its session has not ended. */
    static final String AFFECTED = """
            SELECT b.id, b.resource_id, b.customer_id, b.status, b.start_at, b.end_at
              FROM booking.booking b
             WHERE b.venue_id = :venueId AND b.source = 'ONLINE'
               AND (b.status IN ('PENDING','AWAITING_CUSTOMER_CONFIRMATION')
                    OR (b.status = 'ACTIVE' AND b.end_at > :now))
             ORDER BY b.start_at, b.id
            """;

    private final VenueAnswerStore store;
    private final ReservationLedger ledger;
    private final BookingFeeRefunds refunds;
    private final ScheduledJobs jobs;
    private final Outbox outbox;
    private final ResponseTimerPauses pauses;
    private final LateConfirmClosures closures;
    private final VenueResponseExpiredResolutions resolutions;

    AccountDisableCascade(VenueAnswerStore store, ReservationLedger ledger, BookingFeeRefunds refunds,
                          ScheduledJobs jobs, Outbox outbox, ResponseTimerPauses pauses, LateConfirmClosures closures,
                          VenueResponseExpiredResolutions resolutions) {
        this.store = store;
        this.ledger = ledger;
        this.refunds = refunds;
        this.jobs = jobs;
        this.outbox = outbox;
        this.pauses = pauses;
        this.closures = closures;
        this.resolutions = resolutions;
    }

    @Override
    public List<Affected> affected(CommandContext command, UUID venueId) {
        return command.jdbc().sql(AFFECTED)
                .param("venueId", Objects.requireNonNull(venueId, "venueId"))
                .param("now", OffsetDateTime.ofInstant(command.commandNow(), ZoneOffset.UTC))
                .query((rs, n) -> new Affected(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class),
                        rs.getObject("customer_id", UUID.class), BookingStatus.valueOf(rs.getString("status")),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public void interrupt(CommandContext command, UUID venueId, List<UUID> bookingIds) {
        Objects.requireNonNull(venueId, "venueId");
        Instant at = command.commandNow();
        List<BookingFeeRefunds.Decision> decisions = new ArrayList<>();
        for (UUID bookingId : HoursChangeCancellation.ordered(bookingIds)) {
            Facts booking = store.facts(command, bookingId)
                    .orElseThrow(() -> integrityFailure(bookingId, "no such booking"));
            if (booking.source() != BookingSource.ONLINE || !venueId.equals(booking.venueId())) {
                throw integrityFailure(bookingId, booking.source() + " booking of another venue or not Online");
            }
            switch (booking.status()) {
                case PENDING -> expire(command, booking, at);
                case AWAITING_CUSTOMER_CONFIRMATION -> decisions.add(cancelAwaiting(command, booking, at));
                default -> throw integrityFailure(bookingId, booking.status() + " booking is not interrupted by a "
                        + "Venue Account disable");
            }
        }
        refunds.recordDecisions(command, decisions);
    }

    /** FR-VEN-09: PENDING -> VENUE_RESPONSE_EXPIRED immediately; the response timer stops (arch 06 section 2.8). */
    private void expire(CommandContext command, Facts booking, Instant at) {
        UUID bookingId = booking.bookingId();
        ActiveReservation reservation = ledger.activeOf(command, ReservationOwner.booking(bookingId))
                .orElseThrow(() -> integrityFailure(bookingId, "PENDING booking holds no active reservation"));
        if (booking.responsePaused()) {
            Duration accumulated = store.endResponsePause(command, bookingId, at)
                    .orElseThrow(() -> integrityFailure(bookingId, "the paused response timer did not end"));
            pauses.closeOpen(command, bookingId, at, accumulated);
        }
        if (!store.expire(command, bookingId)) {
            throw integrityFailure(bookingId, "the booking left PENDING under the command's locks");
        }
        ledger.release(command, reservation.id(), at, BookingStatus.VENUE_RESPONSE_EXPIRED.name());
        UUID correlationId = CorrelationId.require();
        store.insertTransition(command, bookingId, at, BookingStatus.PENDING, BookingStatus.VENUE_RESPONSE_EXPIRED,
                null, ActorType.SYSTEM, null, correlationId);
        command.jdbc().sql(JdbcLateConfirmBookings.INSERT_EVENT)
                .param("id", UUID.randomUUID())
                .param("bookingId", bookingId)
                .param("occurredAt", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("eventType", BookingEventType.VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE.name())
                .param("actorType", ActorType.SYSTEM.name())
                .param("actorId", null)
                .param("correlationId", correlationId)
                .update();
        jobs.supersede(command, VenueAnswers.venueResponseExpiryKey(bookingId));
        resolutions.scheduleAtStart(command, bookingId);                                         // T6, E7-118, E3
        outbox.publish(command, BookingEvents.venueResponseInterruptedByAccountDisable(booking));
    }

    /** FR-VEN-09: AWAITING_CUSTOMER_CONFIRMATION -> CANCELLED VENUE_ACCOUNT_DISABLED, its hold released. */
    private BookingFeeRefunds.Decision cancelAwaiting(CommandContext command, Facts booking, Instant at) {
        UUID bookingId = booking.bookingId();
        closures.closeForCancellation(command, bookingId, REASON, at);
        if (!store.terminate(command, bookingId, BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, BookingStatus.CANCELLED,
                REASON, at, null)) {
            throw integrityFailure(bookingId, "the booking left AWAITING_CUSTOMER_CONFIRMATION under the command's "
                    + "locks");
        }
        UUID history = store.insertTransition(command, bookingId, at, BookingStatus.AWAITING_CUSTOMER_CONFIRMATION,
                BookingStatus.CANCELLED, REASON, ActorType.SYSTEM, null, CorrelationId.require());
        outbox.publish(command, BookingEvents.cancelled(booking, REASON));
        return new BookingFeeRefunds.Decision(booking.customerId(), bookingId, history);
    }

    private static IllegalStateException integrityFailure(UUID bookingId, String detail) {
        log.error("Booking integrity failure: {} (bookingId={}, correlationId={})", detail, bookingId,
                CorrelationId.current().map(UUID::toString).orElse("none"));
        return new IllegalStateException("Booking " + bookingId + " integrity failure in the Venue Account disable: "
                + detail);
    }
}
