package ps.e7gz.notification.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * Creates notifications (Spec v5 section 52, arch 08 section 4). Call it from the command whose change the notification
 * reports, on that command's transaction: the notification exists exactly when the change commits, and never for a
 * command that rolls back (FR-NTF-12).
 */
public interface Notifications {

    /**
     * Inserts the {@code notification.notification} row and one {@code notification_delivery} per channel its
     * catalogue code requires (arch 08 section 4.4), in {@code context}'s transaction. The in-app delivery is done by the
     * commit itself (the row is in the recipient's list); PUSH and EMAIL are sent by the worker after commit, through
     * the outbox. The database refuses an unknown code, a recipient the code does not allow, a missing or extra
     * Booking/Tournament reference, a secret in {@code params}, and - at commit - a required channel without its
     * delivery. Returns the notification id.
     */
    UUID notify(CommandContext context, NotificationRequest request);
}
