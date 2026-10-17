package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.BookingStatus;

/**
 * E7-70, FR-ATT-12 at the exact boundary: the real clock cannot land a reversal on endAt, so the decision the booking
 * module takes before the database's own refusal is checked here, to the microsecond the database stores.
 */
class NoShowReversalTargetTest {

    private static final Instant END = Instant.parse("2026-10-18T20:00:00Z");

    @Test
    void beforeTheOriginalEndIsCancelled() {
        assertThat(JdbcAttendanceBookings.reversalTarget(END.minus(Duration.ofNanos(1_000)), END))
                .isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void atTheOriginalEndIsCompleted() {
        assertThat(JdbcAttendanceBookings.reversalTarget(END, END)).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void afterTheOriginalEndIsCompleted() {
        assertThat(JdbcAttendanceBookings.reversalTarget(END.plusSeconds(1), END)).isEqualTo(BookingStatus.COMPLETED);
    }
}
