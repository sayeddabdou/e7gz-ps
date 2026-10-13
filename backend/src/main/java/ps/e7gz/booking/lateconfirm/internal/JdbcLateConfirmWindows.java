package ps.e7gz.booking.lateconfirm.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.LateConfirmWindows;
import ps.e7gz.common.command.CommandContext;

/** Booking's read of the open Late Confirmation deadlines ({@link LateConfirmWindows}), from this module's table. */
@Component
class JdbcLateConfirmWindows implements LateConfirmWindows {

    private final LateConfirmStore store;

    JdbcLateConfirmWindows(LateConfirmStore store) {
        this.store = store;
    }

    @Override
    public Map<UUID, Instant> openDeadlines(CommandContext command, Collection<UUID> bookingIds) {
        return store.openDeadlines(Objects.requireNonNull(command, "command"),
                Objects.requireNonNull(bookingIds, "bookingIds"));
    }
}
