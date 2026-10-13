package ps.e7gz.notification.internal.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.internal.stream.VenueStreamChannel.Reason;
import ps.e7gz.notification.internal.stream.VenueStreamChannel.Signal;
import ps.e7gz.notification.internal.stream.VenueStreamChannel.Type;

/**
 * Turns committed integration events into venue live-channel signals (arch 08 sections 4.1 and 5): the dispatcher
 * calls it after commit, and it sends {@code pg_notify('venue_{venueAccountId}', ...)}, which every API replica with a
 * stream open for that account hears ({@link VenueStreamHub}). A retry sends the signal again, which only makes the
 * Dashboard refetch once more.
 *
 * <ul>
 *   <li>{@code BookingCreated} - {@code BOOKING CREATED} (a new booking request: the audible alert, A6).</li>
 *   <li>{@code BookingConfirmed}, {@code BookingRejected}, {@code VenueResponseExpired}, {@code BookingCancelled}
 *       (E7-47) - {@code BOOKING CHANGED}; the same events of a Manual Booking and {@code BookingStarted} (E7-48) -
 *       {@code BOOKING CHANGED}: the venue made the booking itself, so no alert.</li>
 *   <li>{@code LateConfirmInitiated}, {@code LateConfirmAccepted}, {@code LateConfirmDeclined},
 *       {@code LateConfirmTimedOut} (E7-58, D2) - {@code BOOKING CHANGED}. Not {@code LateConfirmWarningDue}: it is
 *       the customer's warning only.</li>
 *   <li>{@code VenueAccountDisabled} - every open stream of the account closes at once (FR-VEN-08).</li>
 * </ul>
 * The other booking and tournament events (a new tournament request, check-in, ...) add a row to
 * {@link #SIGNALS} when their producer tasks start writing them (arch 08 section 3.2).
 */
@Component
@Profile("worker")
public class VenueStreamSignals implements OutboxConsumer {

    /** An event's signal: its type and reason, and the payload fields naming the entity and the venue. */
    private record Mapping(Type type, Reason reason, String idField) {
    }

    private static final Map<String, Mapping> SIGNALS = Map.of(
            IntegrationEvents.BOOKING_CREATED, new Mapping(Type.BOOKING, Reason.CREATED, "bookingId"),
            // E7-47's answers and expiry: the Pending and Missed lists change (E7-44/E7-47 review, 10 Oct 2026)
            IntegrationEvents.BOOKING_CONFIRMED, new Mapping(Type.BOOKING, Reason.CHANGED, "bookingId"),
            IntegrationEvents.BOOKING_REJECTED, new Mapping(Type.BOOKING, Reason.CHANGED, "bookingId"),
            IntegrationEvents.VENUE_RESPONSE_EXPIRED, new Mapping(Type.BOOKING, Reason.CHANGED, "bookingId"),
            IntegrationEvents.BOOKING_CANCELLED, new Mapping(Type.BOOKING, Reason.CHANGED, "bookingId"),
            // E7-48: a walk-in created ACTIVE; never the CREATED alert, the venue made it (PO decision 10 Oct 2026, E7-48, D6)
            IntegrationEvents.BOOKING_STARTED, new Mapping(Type.BOOKING, Reason.CHANGED, "bookingId"),
            // E7-58: a Late Confirmation sent, accepted, declined or timed out changes the booking and the Missed list
            // (PO decision D2 12 Oct 2026); LateConfirmWarningDue is the customer's only and has no venue signal
            IntegrationEvents.LATE_CONFIRM_INITIATED, new Mapping(Type.BOOKING, Reason.CHANGED, "bookingId"),
            IntegrationEvents.LATE_CONFIRM_ACCEPTED, new Mapping(Type.BOOKING, Reason.CHANGED, "bookingId"),
            IntegrationEvents.LATE_CONFIRM_DECLINED, new Mapping(Type.BOOKING, Reason.CHANGED, "bookingId"),
            IntegrationEvents.LATE_CONFIRM_TIMED_OUT, new Mapping(Type.BOOKING, Reason.CHANGED, "bookingId"));

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public VenueStreamSignals(DataSource appDataSource, ObjectMapper json) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.json = json;
    }

    @Override
    public Set<String> eventTypes() {
        return union(SIGNALS.keySet(), IntegrationEvents.VENUE_ACCOUNT_DISABLED);
    }

    @Override
    public void handle(OutboxEvent event) {
        if (IntegrationEvents.VENUE_ACCOUNT_DISABLED.equals(event.eventType())) {
            notify(event.uuid("venueAccountId"), VenueStreamChannel.close(json));
            return;
        }
        Mapping mapping = SIGNALS.get(event.eventType());
        Optional<UUID> account = accountOf(event.uuid("venueId"));
        // A venue without an account has no Dashboard to tell.
        account.ifPresent(id -> notify(id, VenueStreamChannel.change(
                new Signal(mapping.type(), event.uuid(mapping.idField()), mapping.reason()), json)));
    }

    private Optional<UUID> accountOf(UUID venueId) {
        return jdbc.sql("SELECT id FROM iam.venue_account WHERE venue_id = :venue")
                .param("venue", venueId).query(UUID.class).optional();
    }

    /** Outside any transaction, so it is delivered at once. */
    private void notify(UUID venueAccountId, String message) {
        jdbc.sql("SELECT pg_notify(:channel, :message)")
                .param("channel", VenueStreamChannel.channel(venueAccountId)).param("message", message)
                .query().singleRow();
    }

    private static Set<String> union(Set<String> names, String name) {
        HashSet<String> all = new HashSet<>(names);
        all.add(name);
        return Set.copyOf(all);
    }
}
