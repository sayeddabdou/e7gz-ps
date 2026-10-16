package ps.e7gz.messaging.api;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Tells the Platform Admin that an outbox event reached {@code DEAD_LETTER} (NFR-13, arch 12 section 6.3; E7-41,
 * PO decision 12 Oct 2026). Implemented by the Platform Administration module: the port lives here so Messaging never
 * depends on it. A notification delivery in {@code DEAD_LETTER} raises no alert - the admin "try again" route handles
 * it (PO decision 12 Oct 2026).
 */
public interface DeadLetterAlerts {

    /**
     * Raises one alert in the dispatcher's own transaction ({@code sameTransaction}), so it commits together with the
     * {@code DEAD_LETTER} mark: exactly once per event.
     *
     * @param lastError the failure the event's {@code last_error} records (already shortened and free of secrets)
     */
    void deadLettered(JdbcClient sameTransaction, OutboxEvent event, String lastError);
}
