package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import ps.e7gz.inventory.availability.internal.FreeWindows.Span;

/** E7-115 - the 15-minute Cairo wall-time grid of Nearest Availability's candidate starts (PO decision 12 Oct 2026). */
class StartGridTest {

    private static Instant cairo(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(StartGrid.ZONE).toInstant();
    }

    @Test
    void ceilKeepsAGridInstantAndRoundsEverythingElseUp() {
        assertThat(StartGrid.ceil(cairo("2031-02-03T19:45:00"))).isEqualTo(cairo("2031-02-03T19:45:00"));
        assertThat(StartGrid.ceil(cairo("2031-02-03T19:37:41"))).isEqualTo(cairo("2031-02-03T19:45:00"));
        assertThat(StartGrid.ceil(cairo("2031-02-03T19:30:00").plusNanos(1))).isEqualTo(cairo("2031-02-03T19:45:00"));
        assertThat(StartGrid.ceil(cairo("2031-02-03T23:59:59"))).isEqualTo(cairo("2031-02-04T00:00:00"));
        assertThat(StartGrid.ceil(cairo("2031-02-03T12:10:00"))).isEqualTo(cairo("2031-02-03T12:15:00"));
    }

    @Test
    void firstFitMovesOffGridBoundariesToTheNextGridInstant() {
        Instant from = cairo("2031-02-03T12:00:00");
        Instant until = cairo("2031-02-03T23:00:00");
        Duration hour = Duration.ofMinutes(60);

        assertThat(StartGrid.firstFit(List.of(), cairo("2031-02-03T12:01:00"), until, hour))
                .contains(cairo("2031-02-03T12:15:00"));
        assertThat(StartGrid.firstFit(List.of(new Span(from, cairo("2031-02-03T13:52:00"))), from, until, hour))
                .contains(cairo("2031-02-03T14:00:00"));
        // 13:05-14:05 is free for 60 minutes from 13:05 only: 13:15-14:15 hits the next span, so 16:00.
        assertThat(StartGrid.firstFit(List.of(new Span(from, cairo("2031-02-03T13:05:00")),
                new Span(cairo("2031-02-03T14:05:00"), cairo("2031-02-03T16:00:00"))), from, until, hour))
                .contains(cairo("2031-02-03T16:00:00"));
        assertThat(StartGrid.firstFit(List.of(), cairo("2031-02-03T22:01:00"), until, hour)).isEmpty();
    }
}
