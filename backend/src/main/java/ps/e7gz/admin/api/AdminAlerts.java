package ps.e7gz.admin.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Raises Platform Admin alerts (NFR-29, arch 11 section 5.4; E7-56). Every alert goes through the one producer
 * {@code admin.raise_alert}; nothing else writes {@code admin.admin_alert}, and an alert is never edited except by its
 * one acknowledgement.
 */
public interface AdminAlerts {

    /** In the caller's command: the alert commits or rolls back with it (e.g. E7-60's integrity cancellation). */
    UUID raise(CommandContext command, NewAdminAlert alert);

    /**
     * For a failure that rolls its command back (E7-47 D11): when the current transaction rolls back, the alert is
     * raised in a transaction of its own, so the rollback cannot take it away; if it commits, nothing is raised. With no
     * transaction it is raised at once. Raised at most once while the same alert (correlation ID aside) is still open,
     * so the bounded retries of a failing job, or another request on the same broken booking, do not repeat it. Never throws: a failing alert is logged.
     */
    void raiseAfterRollback(NewAdminAlert alert);
}
