package ps.e7gz.inventory.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.anOpeningTemplate;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.inventory.availability.internal.BusinessDatesWithClock;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.OperatingSessionFixtures;
import ps.e7gz.venue.api.OperatingSessions;

/**
 * E7-31 - the business date (arch 04 section 4.3, FR-AVL-14) from real {@code venue.operating_session} rows. Each Venue
 * has a weekly template of 10:00-12:00 that the rows disagree with, so a date taken from the template would fail. The
 * DST dates are E7-25's: Africa/Cairo goes forward at Friday 24 April 2026 00:00 and back at Thursday 29 October 24:00.
 * The Venues and their rows come from the shared fixture builders (E7-28).
 */
class BusinessDateIntegrationTest extends IntegrationTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    @Autowired
    CommandRunner runner;

    @Autowired
    BusinessDates dates;

    @Autowired
    OperatingSessions sessions;

    /** No opening hours; each test writes its template and sessions inside a command and rolls back. */
    private static UUID venue;
    /** Committed: template 10:00-12:00 and one past session, Wednesday 10 June 2025 18:00 -> Thursday 02:00 Cairo. */
    private static UUID committed;

    /** After the context started, so Flyway has migrated the shared database; once per JVM. */
    @BeforeEach
    void fixtures() {
        if (venue != null) {
            return;
        }
        venue = aVenue().create().id();
        Fixtures.Venue withHours = aVenue().create();
        UUID template = anOpeningTemplate().forVenue(withHours).hours("10:00", "12:00").create();
        anOperatingSession().forVenue(withHours).template(template)
                .on("2025-06-10", "2025-06-10T18:00", "2025-06-11T02:00").create();
        committed = withHours.id();
    }

    private static Instant cairo(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(CAIRO).toInstant();
    }

    /** The materialized rows of every case; none of them is what the 10:00-12:00 template would generate. */
    private static void sessions(CommandContext ctx) {
        UUID template = anOpeningTemplate().forVenue(venue).hours("10:00", "12:00").create(ctx.jdbc());
        session(ctx, template, "2026-11-04", "2026-11-04T13:00", "2026-11-04T23:00");     // same day
        session(ctx, template, "2026-11-05", "2026-11-05T18:00", "2026-11-06T02:00");     // cross midnight
        session(ctx, template, "2026-04-23", "2026-04-23T18:00", "2026-04-24T02:00");     // spring forward
        session(ctx, template, "2026-10-29", "2026-10-29T18:00", "2026-10-30T02:00");     // fall back
    }

    private static void session(CommandContext ctx, UUID template, String day, String localStart, String localEnd) {
        anOperatingSession().forVenue(venue).template(template).on(day, localStart, localEnd).create(ctx.jdbc());
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // 1 - normal same-day session
                Arguments.of("same-day session", cairo("2026-11-04T15:00"), "2026-11-04"),
                Arguments.of("template hours, no row: the calendar date", cairo("2026-11-04T11:00"), "2026-11-04"),
                // 2 - cross-midnight 18:00-02:00, 01:00 -> the previous operating day
                Arguments.of("cross midnight, 01:00", cairo("2026-11-06T01:00"), "2026-11-05"),
                // 3 - out of hours: closed at 02:00, a Manual Booking at 03:00 -> that Cairo calendar date
                Arguments.of("out of hours, 03:00 after the 02:00 close", cairo("2026-11-06T03:00"), "2026-11-06"),
                Arguments.of("out of hours, exactly at the 02:00 close", cairo("2026-11-06T02:00"), "2026-11-06"),
                // 4 - spring forward: Friday 00:00-00:59 does not exist
                Arguments.of("spring forward, Friday 01:30 (+03) in Thursday's session",
                        Instant.parse("2026-04-23T22:30:00Z"), "2026-04-23"),
                Arguments.of("spring forward, Friday 02:00 (+03) after the close",
                        Instant.parse("2026-04-23T23:00:00Z"), "2026-04-24"),
                // 5 - fall back: Thursday 23:00-23:59 happens twice
                Arguments.of("fall back, the second 23:30 (+02)", Instant.parse("2026-10-29T21:30:00Z"), "2026-10-29"),
                Arguments.of("fall back, Friday 01:30 (+02) in Thursday's session",
                        Instant.parse("2026-10-29T23:30:00Z"), "2026-10-29"),
                Arguments.of("fall back, Friday 02:00 (+02) after the close", Instant.parse("2026-10-30T00:00:00Z"),
                        "2026-10-30"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theBusinessDateOfAStartIsItsSessionsOperatingDayElseItsCairoDate(String name, Instant startAt,
                                                                          String expected) {
        AtomicReference<LocalDate> resolved = new AtomicReference<>();
        OperatingSessionFixtures.inRolledBackCommand(runner, ctx -> {
            sessions(ctx);
            resolved.set(dates.businessDate(ctx, venue, startAt));
        });

        assertThat(resolved.get()).isEqualTo(expected);
    }

    @Test
    void aVenueWithNoSessionsStillGetsADate() {
        AtomicReference<LocalDate> resolved = new AtomicReference<>();
        OperatingSessionFixtures.inRolledBackCommand(runner,
                ctx -> resolved.set(dates.businessDate(ctx, UUID.randomUUID(), cairo("2026-11-06T01:00"))));

        assertThat(resolved.get()).isEqualTo("2026-11-06");
    }

    /** The committed Venue: 10 June 2025, 18:00 -> 11 June 02:00 Cairo. */
    @Test
    void theCurrentBusinessDateAt0100InsideAnOvernightSessionIsThePreviousDay() {
        Clock clock = Clock.fixed(cairo("2025-06-11T01:00"), ZoneOffset.UTC);

        assertThat(BusinessDatesWithClock.of(sessions, clock).currentBusinessDate(committed))
                .isEqualTo("2025-06-10");
    }

    @Test
    void theCurrentBusinessDateAt0300AfterTheSessionEndedIsThatDay() {
        Clock clock = Clock.fixed(cairo("2025-06-11T03:00"), ZoneOffset.UTC);

        assertThat(BusinessDatesWithClock.of(sessions, clock).currentBusinessDate(committed))
                .isEqualTo("2025-06-11");
    }

    @Test
    void theCurrentBusinessDateInTemplateHoursWithoutARowIsTheCalendarDate() {
        Clock clock = Clock.fixed(cairo("2025-06-10T11:00"), ZoneOffset.UTC);

        assertThat(BusinessDatesWithClock.of(sessions, clock).currentBusinessDate(committed))
                .isEqualTo("2025-06-10");
    }
}
