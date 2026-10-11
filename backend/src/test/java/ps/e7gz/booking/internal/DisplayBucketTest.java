package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.codes.BookingStatus;

/** E7-49 - the customer-facing projection of arch 06 section 1.5 / FR-BOK-19, status by status. */
class DisplayBucketTest {

    static final Map<BookingStatus, DisplayBucket> FR_BOK_19 = Map.of(
            BookingStatus.PENDING, DisplayBucket.UPCOMING,
            BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, DisplayBucket.UPCOMING,
            BookingStatus.CONFIRMED, DisplayBucket.UPCOMING,
            BookingStatus.ACTIVE, DisplayBucket.UPCOMING,
            BookingStatus.COMPLETED, DisplayBucket.COMPLETED,
            BookingStatus.VENUE_RESPONSE_EXPIRED, DisplayBucket.CANCELLED,
            BookingStatus.CANCELLED, DisplayBucket.CANCELLED,
            BookingStatus.REJECTED, DisplayBucket.CANCELLED,
            BookingStatus.NO_SHOW, DisplayBucket.CANCELLED);

    @Test
    void everyStatusHasExactlyTheBucketOfFrBok19() {
        assertThat(FR_BOK_19.keySet()).as("the table covers every status").isEqualTo(EnumSet.allOf(BookingStatus.class));
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(DisplayBucket.of(status)).as(status.name()).isEqualTo(FR_BOK_19.get(status));
        }
    }

    @Test
    void theBucketsPartitionTheStatuses() {
        Set<BookingStatus> seen = EnumSet.noneOf(BookingStatus.class);
        for (DisplayBucket bucket : DisplayBucket.values()) {
            for (BookingStatus status : bucket.statuses()) {
                assertThat(seen.add(status)).as("%s in one bucket only", status).isTrue();
            }
        }
        assertThat(seen).isEqualTo(EnumSet.allOf(BookingStatus.class));
        assertThat(DisplayBucket.CANCELLED.statuses()).as("FR-BOK-20: shown under Cancelled, still revivable")
                .contains(BookingStatus.VENUE_RESPONSE_EXPIRED);
    }

    @Test
    void onlyThreeBucketsExist() {
        assertThat(Arrays.stream(DisplayBucket.values()).map(Enum::name))
                .containsExactly("UPCOMING", "COMPLETED", "CANCELLED");
    }
}
