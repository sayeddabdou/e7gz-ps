package ps.e7gz.attendance.internal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.AttendanceEvidence;
import ps.e7gz.common.command.CommandContext;

/** Booking's read of the attendance records (E7-59): evidence a Platform Admin reversed is absent (FR-ATT-11). */
@Component
class JdbcAttendanceEvidence implements AttendanceEvidence {

    private final AttendanceStore store;

    JdbcAttendanceEvidence(AttendanceStore store) {
        this.store = store;
    }

    @Override
    public Map<UUID, Recorded> of(CommandContext command, Collection<UUID> bookingIds) {
        return store.find(command, bookingIds).stream().collect(Collectors.toUnmodifiableMap(
                AttendanceStore.Evidence::bookingId, JdbcAttendanceEvidence::recorded));
    }

    static Recorded recorded(AttendanceStore.Evidence evidence) {
        return new Recorded(evidence.result(), evidence.activeCheckIn(), evidence.activeLatePresent(),
                evidence.activeNoShow());
    }
}
