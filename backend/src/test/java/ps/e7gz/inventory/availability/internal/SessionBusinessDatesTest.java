package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.venue.api.OperatingSessions;

/**
 * E7-31 - the two business-date rules (arch 04 section 4.3, FR-AVL-14), table-driven over materialized sessions given
 * as absolute instants. The DST dates are E7-25's ({@code SessionGeneratorTest}): Africa/Cairo in 2026 goes forward
 * at 00:00 on Friday 24 April and back at 24:00 on Thursday 29 October. {@code BusinessDateIntegrationTest} repeats the
 * cases against real {@code venue.operating_session} rows.
 */
class SessionBusinessDatesTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final UUID VENUE = UUID.fromString("e7310000-0000-4000-8000-0000000000aa");

    /** A materialized row: {@code operating_day} and its {@code session_period [start, end)}. */
    record Session(LocalDate operatingDay, Instant start, Instant end) {
    }

    /** The owner read, faked with the same {@code [)} containment the SQL uses. */
    record FakeSessions(List<Session> rows) implements OperatingSessions {

        @Override
        public Optional<LocalDate> operatingDayAt(CommandContext command, UUID venueId, Instant at) {
            return operatingDayAt(venueId, at);
        }

        @Override
        public Optional<LocalDate> operatingDayAt(UUID venueId, Instant at) {
            return rows.stream().filter(s -> !at.isBefore(s.start()) && at.isBefore(s.end()))
                    .map(Session::operatingDay).findFirst();
        }
    }

    private static Instant cairo(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(CAIRO).toInstant();
    }

    private static Session session(String day, Instant start, Instant end) {
        return new Session(LocalDate.parse(day), start, end);
    }

    // Wednesday 7 October 2026 (no DST change near): 12:00-23:00, and a cross-midnight 18:00-02:00.
    private static final Session SAME_DAY = session("2026-10-07", cairo("2026-10-07T12:00"), cairo("2026-10-07T23:00"));
    private static final Session CROSS_MIDNIGHT =
            session("2026-10-08", cairo("2026-10-08T18:00"), cairo("2026-10-09T02:00"));
    // Thursday 23 April 18:00 (+02:00) -> Friday 02:00 (+03:00): 7 real hours.
    private static final Session SPRING_FORWARD =
            session("2026-04-23", Instant.parse("2026-04-23T16:00:00Z"), Instant.parse("2026-04-23T23:00:00Z"));
    // Thursday 29 October 18:00 (+03:00) -> Friday 02:00 (+02:00): 9 real hours, 23:00-23:59 twice.
    private static final Session FALL_BACK =
            session("2026-10-29", Instant.parse("2026-10-29T15:00:00Z"), Instant.parse("2026-10-30T00:00:00Z"));

    private static final List<Session> ROWS = List.of(SAME_DAY, CROSS_MIDNIGHT, SPRING_FORWARD, FALL_BACK);

    static Stream<Arguments> cases() {
        return Stream.of(
                // 1 - normal same-day session
                Arguments.of("same-day session, inside", cairo("2026-10-07T15:00"), "2026-10-07"),
                Arguments.of("same-day session, at its start", SAME_DAY.start(), "2026-10-07"),
                Arguments.of("same-day session, at its end: outside, same calendar day", SAME_DAY.end(), "2026-10-07"),
                // 2 - cross-midnight session: 01:00 belongs to the previous operating day
                Arguments.of("cross-midnight, 01:00 next morning", cairo("2026-10-09T01:00"), "2026-10-08"),
                Arguments.of("cross-midnight, 23:00 same evening", cairo("2026-10-08T23:00"), "2026-10-08"),
                // 3 - out of hours: closed at 02:00, a manual start at 03:00 is that calendar day
                Arguments.of("out of hours, 03:00 after a 02:00 close", cairo("2026-10-09T03:00"), "2026-10-09"),
                Arguments.of("out of hours, at the 02:00 close", CROSS_MIDNIGHT.end(), "2026-10-09"),
                Arguments.of("out of hours, morning before opening", cairo("2026-10-07T09:00"), "2026-10-07"),
                // 4 - spring forward: 00:00-00:59 Friday never happens
                Arguments.of("spring forward, Friday 01:30 (+03) in Thursday's session",
                        Instant.parse("2026-04-23T22:30:00Z"), "2026-04-23"),
                Arguments.of("spring forward, Friday 01:00 (+03), the first instant after the gap",
                        Instant.parse("2026-04-23T22:00:00Z"), "2026-04-23"),
                Arguments.of("spring forward, out of hours at the Friday 02:00 (+03) close",
                        SPRING_FORWARD.end(), "2026-04-24"),
                // 5 - fall back: 23:00-23:59 Thursday happens twice
                Arguments.of("fall back, first 23:30 (+03)", Instant.parse("2026-10-29T20:30:00Z"), "2026-10-29"),
                Arguments.of("fall back, second 23:30 (+02)", Instant.parse("2026-10-29T21:30:00Z"), "2026-10-29"),
                Arguments.of("fall back, Friday 01:30 (+02) in Thursday's session",
                        Instant.parse("2026-10-29T23:30:00Z"), "2026-10-29"),
                Arguments.of("fall back, out of hours at the Friday 02:00 (+02) close", FALL_BACK.end(),
                        "2026-10-30"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void businessDateIsTheSessionsOperatingDayElseTheCairoCalendarDate(String name, Instant startAt, String expected) {
        BusinessDates dates = new SessionBusinessDates(new FakeSessions(ROWS), Clock.fixed(startAt, ZoneOffset.UTC));

        assertThat(dates.businessDate(mock(CommandContext.class), VENUE, startAt)).isEqualTo(expected);
        assertThat(dates.currentBusinessDate(VENUE)).as("the same rule from the Clock").isEqualTo(expected);
    }

    static Stream<Arguments> outOfSession() {
        return Stream.of(
                Arguments.of("Cairo 23:59 (+02) is not yet the UTC date", Instant.parse("2026-01-01T21:59:00Z"),
                        "2026-01-01"),
                Arguments.of("Cairo midnight (+02) is the next date while UTC is still the old one",
                        Instant.parse("2026-01-01T22:00:00Z"), "2026-01-02"),
                Arguments.of("Cairo midnight (+03) in summer", Instant.parse("2026-07-01T21:00:00Z"), "2026-07-02"),
                Arguments.of("Cairo 23:59 (+03) in summer", Instant.parse("2026-07-01T20:59:00Z"), "2026-07-01"),
                // spring forward: Thursday 23:59 (+02) is followed by Friday 01:00 (+03)
                Arguments.of("spring forward, Thursday 23:59 (+02)", Instant.parse("2026-04-23T21:59:00Z"),
                        "2026-04-23"),
                Arguments.of("spring forward, Friday 01:00 (+03)", Instant.parse("2026-04-23T22:00:00Z"),
                        "2026-04-24"),
                // fall back: both 23:30s are Thursday; the following midnight (+02) is Friday
                Arguments.of("fall back, first 23:30 (+03)", Instant.parse("2026-10-29T20:30:00Z"), "2026-10-29"),
                Arguments.of("fall back, second 23:30 (+02)", Instant.parse("2026-10-29T21:30:00Z"), "2026-10-29"),
                Arguments.of("fall back, Friday 00:00 (+02)", Instant.parse("2026-10-29T22:00:00Z"), "2026-10-30"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outOfSession")
    void withNoSessionTheFallbackIsNeverNullAndUsesTheCairoZoneRules(String name, Instant at, String expected) {
        BusinessDates dates = new SessionBusinessDates(new FakeSessions(List.of()),
                Clock.fixed(at, ZoneId.of("Pacific/Kiritimati")));      // the Clock's own zone is never used

        assertThat(dates.businessDate(mock(CommandContext.class), VENUE, at)).isNotNull().isEqualTo(expected);
        assertThat(dates.currentBusinessDate(VENUE)).isNotNull().isEqualTo(expected);
    }
}
