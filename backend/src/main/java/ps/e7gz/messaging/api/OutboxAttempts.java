package ps.e7gz.messaging.api;

/**
 * Tells an {@link OutboxConsumer} whether the attempt it is running is the event's last (arch 08 section 4.3): when it
 * fails too, the dispatcher moves the event to {@code DEAD_LETTER}. A consumer that keeps its own delivery state (the
 * notification deliveries, E7-43) uses it to make that state final on the same attempt. Worker profile only.
 */
@FunctionalInterface
public interface OutboxAttempts {

    /** {@code true} when this attempt of {@code event} is the last one the dispatcher makes. */
    boolean isLast(OutboxEvent event);
}
