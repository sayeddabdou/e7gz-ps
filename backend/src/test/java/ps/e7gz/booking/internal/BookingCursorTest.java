package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/** E7-49 - the booking lists' cursor and the venue filter's own validation. */
class BookingCursorTest {

    @Test
    void aCursorRoundTripsToTheMicrosecond() {
        BookingCursor.Position position = new BookingCursor.Position(Instant.parse("2026-10-12T18:00:00.123456Z"),
                UUID.randomUUID());

        assertThat(BookingCursor.decode(BookingCursor.encode(position))).isEqualTo(position);
        assertThat(BookingCursor.decode(null)).isNull();
        assertThat(BookingCursor.decode("  ")).isNull();
    }

    @Test
    void anythingElseIsValidationFailedOnCursor() {
        for (String bad : new String[] {"not-a-cursor", "eHl6", BookingCursor.encode(
                new BookingCursor.Position(Instant.EPOCH, UUID.randomUUID())).substring(3)}) {
            assertThatThrownBy(() -> BookingCursor.decode(bad))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
                        assertThat(e.field()).isEqualTo("cursor");
                    });
        }
    }

    @Test
    void theVenueFilterRefusesFromAfterToAndTrimsTheSearch() {
        assertThatThrownBy(() -> new VenueBookingFilter(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 11),
                null, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        LocalDate day = LocalDate.of(2026, 10, 12);
        assertThat(new VenueBookingFilter(day, day, null, null, null, null, "   ").q()).isNull();
        assertThat(new VenueBookingFilter(null, null, null, null, null, null, " Omar ").q()).isEqualTo("Omar");
        assertThat(new VenueBookingFilter(null, null, null, null, null, null, "50%_a\\b").likePattern())
                .isEqualTo("%50\\%\\_a\\\\b%");
    }
}
