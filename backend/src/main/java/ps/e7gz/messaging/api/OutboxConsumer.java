package ps.e7gz.messaging.api;

import java.util.Set;

/**
 * Takes committed integration events from the outbox dispatcher (arch 08 section 4.1): notifications (E7-43), the venue
 * live channel (E7-44), projections. Each consumer is a Spring bean of the worker profile; the dispatcher finds every
 * bean of this type, so a module adds a consumer without touching the dispatcher (E7-41). An event type no consumer takes
 * is never polled: its events wait, {@code PENDING}, until one does.
 *
 * <p>Delivery is at least once. When any consumer of an event fails, the event is retried later and EVERY consumer of
 * its type runs again, so {@link #handle} must be safe to run twice for the same event (no second visible effect).
 * Consumers of one event run one after the other on the dispatcher's thread, outside any transaction; a consumer that
 * writes runs its own command.
 */
public interface OutboxConsumer {

    /** The {@code event_type} values it takes, e.g. {@code VenueMarketplaceStatusChanged}. */
    Set<String> eventTypes();

    /**
     * Handles one event. Return normally when it is done (or there is nothing to do any more). Throw to have the event
     * retried after its back-off (arch 08 section 4.3); throw {@link PermanentDeliveryFailure} when a retry can never
     * succeed, and the event goes straight to {@code DEAD_LETTER}.
     */
    void handle(OutboxEvent event);
}
