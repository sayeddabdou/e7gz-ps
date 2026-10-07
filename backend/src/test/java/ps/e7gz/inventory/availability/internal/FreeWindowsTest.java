package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ps.e7gz.inventory.availability.internal.FreeWindows.Span;

/**
 * E7-33 - the free-gap boundary search of arch 05 section 5.2: candidates are the search start and the ends of busy
 * spans, never minutes stepped through.
 */
class FreeWindowsTest {

    private static final Duration HOUR = Duration.ofMinutes(60);

    private static Instant at(String hhmmss) {
        return Instant.parse("2031-02-03T" + hhmmss + "Z");
    }

    private static Span span(String start, String end) {
        return new Span(at(start), at(end));
    }

    // ---- union --------------------------------------------------------------------------------------------------

    @Test
    void overlappingSpansAreMergedIntoOne() {
        assertThat(FreeWindows.union(List.of(span("12:00:00", "13:00:00"), span("12:30:00", "14:00:00"))))
                .containsExactly(span("12:00:00", "14:00:00"));
    }

    @Test
    void backToBackSpansAreMergedBecauseAZeroLengthGapHoldsNoWindow() {
        assertThat(FreeWindows.union(List.of(span("13:00:00", "14:00:00"), span("12:00:00", "13:00:00"))))
                .containsExactly(span("12:00:00", "14:00:00"));
    }

    @Test
    void aContainedSpanAddsNothingAndSeparateSpansStaySeparateInOrder() {
        assertThat(FreeWindows.union(List.of(span("16:00:00", "17:00:00"), span("12:00:00", "15:00:00"),
                span("12:30:00", "13:00:00"))))
                .containsExactly(span("12:00:00", "15:00:00"), span("16:00:00", "17:00:00"));
    }

    // ---- minus (E7-112) -----------------------------------------------------------------------------------------

    @Test
    void minusWithNothingBusyLeavesTheBaseWhole() {
        assertThat(FreeWindows.minus(List.of(span("10:00:00", "23:00:00")), List.of()))
                .containsExactly(span("10:00:00", "23:00:00"));
    }

    @Test
    void minusCutsHalfOpenHolesAndKeepsTheEdgesFree() {
        List<Span> busy = FreeWindows.union(List.of(span("12:00:00", "13:00:00"), span("12:30:00", "13:30:00"),
                span("15:00:00", "16:00:00")));

        assertThat(FreeWindows.minus(List.of(span("10:00:00", "23:00:00")), busy))
                .containsExactly(span("10:00:00", "12:00:00"), span("13:30:00", "15:00:00"),
                        span("16:00:00", "23:00:00"));
    }

    @Test
    void minusIgnoresBusyTimeOutsideTheBaseAndClipsBusyTimeAcrossItsEdges() {
        List<Span> busy = List.of(span("03:00:00", "04:00:00"), span("09:00:00", "11:00:00"),
                span("22:00:00", "23:30:00"));

        assertThat(FreeWindows.minus(List.of(span("10:00:00", "23:00:00")), busy))
                .as("03:00-04:00 outside the session creates no FREE around it")
                .containsExactly(span("11:00:00", "22:00:00"));
    }

    @Test
    void minusOfAFullyCoveredBaseIsEmpty() {
        assertThat(FreeWindows.minus(List.of(span("10:00:00", "12:00:00")), List.of(span("09:00:00", "13:00:00"))))
                .isEmpty();
    }

    // ---- firstFit -----------------------------------------------------------------------------------------------

    @Test
    void withNothingBusyTheSearchStartIsTheCandidate() {
        assertThat(FreeWindows.firstFit(List.of(), at("12:00:00"), at("23:00:00"), HOUR)).contains(at("12:00:00"));
    }

    @Test
    void anExactSixtyMinuteGapIsAccepted() {
        List<Span> busy = List.of(span("12:00:00", "13:00:00"), span("14:00:00", "23:00:00"));

        assertThat(FreeWindows.firstFit(busy, at("12:00:00"), at("23:00:00"), HOUR)).contains(at("13:00:00"));
    }

    @Test
    void aGapShorterThanSixtyMinutesIsSkippedForTheNextBoundary() {
        List<Span> busy = List.of(span("12:00:00", "13:00:00"), span("13:59:59", "15:00:00"));

        assertThat(FreeWindows.firstFit(busy, at("12:00:00"), at("23:00:00"), HOUR)).contains(at("15:00:00"));
    }

    @Test
    void aWindowThatWouldRunPastTheSessionEndIsRefused() {
        assertThat(FreeWindows.firstFit(List.of(), at("22:00:01"), at("23:00:00"), HOUR)).isEmpty();
        assertThat(FreeWindows.firstFit(List.of(), at("22:00:00"), at("23:00:00"), HOUR)).contains(at("22:00:00"));
        assertThat(FreeWindows.firstFit(List.of(span("12:00:00", "22:30:00")), at("12:00:00"), at("23:00:00"), HOUR))
                .isEmpty();
    }

    @Test
    void aBusySpanStartingBeforeTheSearchStartMovesTheCandidateToItsEnd() {
        assertThat(FreeWindows.firstFit(List.of(span("09:00:00", "12:20:00")), at("12:15:00"), at("23:00:00"), HOUR))
                .contains(at("12:20:00"));
    }

    @Test
    void spansThatEndedBeforeTheSearchStartAreIgnored() {
        assertThat(FreeWindows.firstFit(List.of(span("09:00:00", "12:00:00")), at("12:00:00"), at("23:00:00"), HOUR))
                .contains(at("12:00:00"));
    }

    @Test
    void theCandidateIsTheExactBoundaryNotAMinuteStep() {
        // A busy span ending at 12:07:13.5: a minute-by-minute scan could only answer 12:08.
        Instant oddEnd = at("12:07:13").plusMillis(500);
        List<Span> busy = List.of(new Span(at("12:00:00"), oddEnd));

        assertThat(FreeWindows.firstFit(busy, at("12:00:00"), at("23:00:00"), HOUR)).contains(oddEnd);
    }

    @Test
    void theSearchVisitsEachBusySpanOnceEvenOverAThousandSpans() {
        // 1,000 busy spans of 59 minutes separated by 59-minute gaps: no gap fits until after the last span.
        List<Span> busy = new ArrayList<>();
        Instant cursor = at("00:00:00");
        for (int i = 0; i < 1000; i++) {
            busy.add(new Span(cursor, cursor.plus(Duration.ofMinutes(59))));
            cursor = cursor.plus(Duration.ofMinutes(118));
        }
        Instant lastEnd = busy.get(busy.size() - 1).end();

        assertThat(FreeWindows.firstFit(FreeWindows.union(busy), at("00:00:00"), lastEnd.plus(HOUR), HOUR))
                .contains(lastEnd);
    }
}
