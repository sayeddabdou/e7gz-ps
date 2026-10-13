package ps.e7gz.notification.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/**
 * The venue's in-Dashboard notification list (FR-NTF-09, arch 08 section 4.4; E7-55, PO decision 12 Oct 2026): the
 * notifications of the signed-in account's venue - a VENUE recipient is the {@code venue.venue} row, and the venue has
 * one shared account (FR-VEN-01). Same paging and read rules as the customer's list ({@link CustomerNotifications}).
 */
@Component
public class VenueNotifications {

    public record Item(UUID id, String code, UUID bookingId, UUID tournamentId, Map<String, Object> params,
                       Instant readAt, Instant createdAt) {
    }

    public record Page(List<Item> items, String nextCursor) {
    }

    private final CustomerNotifications lists;
    private final JdbcClient jdbc;

    VenueNotifications(CustomerNotifications lists, DataSource appDataSource) {
        this.lists = lists;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** Newest first; the cursor is opaque, a malformed one is {@code VALIDATION_FAILED} on {@code cursor}. */
    public Page list(UUID venueAccountId, String cursor, Integer limit) {
        CustomerNotifications.Page page = lists.list(NotificationRecipientType.VENUE, venueOf(venueAccountId),
                cursor, limit);
        return new Page(page.items().stream().map(item -> new Item(item.id(), item.code(), item.bookingId(),
                item.tournamentId(), item.params(), item.readAt(), item.createdAt())).toList(), page.nextCursor());
    }

    /** An already-read one keeps its first read time; another venue's notification is {@code NOT_FOUND}. */
    public void markRead(UUID venueAccountId, UUID notificationId) {
        UUID venueId = venueOf(venueAccountId);
        lists.markRead(NotificationRecipientType.VENUE, venueId, EligibilityCategory.C_INTERNAL_VENUE_OPERATION,
                LockSet.builder().venues(venueId).build(), notificationId);
    }

    /**
     * The account's venue. Read here, not through venue.api: the venue module already depends on notifications, so the
     * reverse would be a module cycle (as in {@link VenuePushSubscriptions}).
     */
    private UUID venueOf(UUID venueAccountId) {
        return jdbc.sql("SELECT venue_id FROM iam.venue_account WHERE id = :id").param("id", venueAccountId)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }
}
