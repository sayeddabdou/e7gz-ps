package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.venue.OperatingSessionFixtures.COMMITTED;
import static ps.e7gz.venue.OperatingSessionFixtures.NEIGHBOUR;
import static ps.e7gz.venue.OperatingSessionFixtures.VENUE;
import static ps.e7gz.venue.OperatingSessionFixtures.session;
import static ps.e7gz.venue.OperatingSessionFixtures.template;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.venue.api.OperatingSessions;

/**
 * E7-31 - {@link OperatingSessions} against the migrated database: the materialized {@code session_period} is the
 * authority, contains its start and not its end, belongs to its own Venue only, and wins over the weekly template.
 * Sessions are written inside a command that is rolled back, so no other test sees them.
 */
class OperatingSessionsIntegrationTest extends IntegrationTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    @Autowired
    CommandRunner runner;

    @Autowired
    OperatingSessions sessions;

    @BeforeEach
    void fixtures() {
        OperatingSessionFixtures.load();
    }

    private void inRolledBackCommand(Consumer<CommandContext> body) {
        OperatingSessionFixtures.inRolledBackCommand(runner, body);
    }

    private static Instant cairo(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(CAIRO).toInstant();
    }

    @Test
    void theSessionPeriodContainsItsStartAndNotItsEnd() {
        inRolledBackCommand(ctx -> {
            session(ctx, VENUE, template(ctx, VENUE), "2026-11-05", "2026-11-05T18:00", "2026-11-06T02:00");
            Instant start = cairo("2026-11-05T18:00");
            Instant end = cairo("2026-11-06T02:00");

            assertThat(sessions.operatingDayAt(ctx, VENUE, start.minus(1, ChronoUnit.MICROS))).isEmpty();
            assertThat(sessions.operatingDayAt(ctx, VENUE, start)).contains(LocalDate.parse("2026-11-05"));
            assertThat(sessions.operatingDayAt(ctx, VENUE, cairo("2026-11-06T01:00")))
                    .contains(LocalDate.parse("2026-11-05"));
            assertThat(sessions.operatingDayAt(ctx, VENUE, end.minus(1, ChronoUnit.MICROS)))
                    .contains(LocalDate.parse("2026-11-05"));
            assertThat(sessions.operatingDayAt(ctx, VENUE, end)).as("[) - the end is outside").isEmpty();
        });
    }

    @Test
    void touchingSessionsHandOverAtTheBoundary() {
        inRolledBackCommand(ctx -> {
            UUID template = template(ctx, VENUE);
            session(ctx, VENUE, template, "2026-11-05", "2026-11-05T18:00", "2026-11-06T02:00");
            session(ctx, VENUE, template, "2026-11-06", "2026-11-06T02:00", "2026-11-06T12:00");

            assertThat(sessions.operatingDayAt(ctx, VENUE, cairo("2026-11-06T02:00")))
                    .contains(LocalDate.parse("2026-11-06"));
        });
    }

    @Test
    void theRowsAreTheAuthorityNotTheWeeklyTemplate() {
        inRolledBackCommand(ctx -> {
            session(ctx, VENUE, template(ctx, VENUE), "2026-11-05", "2026-11-05T18:00", "2026-11-06T02:00");

            // The template says 10:00-12:00; no row covers 11:00, so nothing contains it.
            assertThat(sessions.operatingDayAt(ctx, VENUE, cairo("2026-11-05T11:00"))).isEmpty();
            assertThat(sessions.operatingDayAt(ctx, VENUE, cairo("2026-11-05T20:00")))
                    .contains(LocalDate.parse("2026-11-05"));
        });
    }

    @Test
    void anotherVenuesSessionOrAnUnknownVenueGivesNothing() {
        inRolledBackCommand(ctx -> {
            session(ctx, NEIGHBOUR, template(ctx, NEIGHBOUR), "2026-11-05", "2026-11-05T18:00", "2026-11-06T02:00");

            assertThat(sessions.operatingDayAt(ctx, VENUE, cairo("2026-11-05T20:00"))).isEmpty();
            assertThat(sessions.operatingDayAt(ctx, UUID.randomUUID(), cairo("2026-11-05T20:00"))).isEmpty();
            assertThat(sessions.operatingDayAt(ctx, NEIGHBOUR, cairo("2026-11-05T20:00")))
                    .contains(LocalDate.parse("2026-11-05"));
        });
    }

    @Test
    void spanningTheDstChangesTheAbsolutePeriodDecides() {
        inRolledBackCommand(ctx -> {
            UUID template = template(ctx, VENUE);
            // E7-25's dates: spring forward at Friday 24 April 2026 00:00, fall back at Thursday 29 October 24:00.
            session(ctx, VENUE, template, "2026-04-23", "2026-04-23T18:00", "2026-04-24T02:00");
            session(ctx, VENUE, template, "2026-10-29", "2026-10-29T18:00", "2026-10-30T02:00");

            assertThat(sessions.operatingDayAt(ctx, VENUE, Instant.parse("2026-04-23T22:00:00Z")))
                    .as("Friday 01:00 (+03), right after the gap").contains(LocalDate.parse("2026-04-23"));
            assertThat(sessions.operatingDayAt(ctx, VENUE, Instant.parse("2026-04-23T23:00:00Z")))
                    .as("Friday 02:00 (+03), the end").isEmpty();
            assertThat(sessions.operatingDayAt(ctx, VENUE, Instant.parse("2026-10-29T21:30:00Z")))
                    .as("the second 23:30 (+02)").contains(LocalDate.parse("2026-10-29"));
            assertThat(sessions.operatingDayAt(ctx, VENUE, Instant.parse("2026-10-29T23:59:00Z")))
                    .as("Friday 01:59 (+02)").contains(LocalDate.parse("2026-10-29"));
            assertThat(sessions.operatingDayAt(ctx, VENUE, Instant.parse("2026-10-30T00:00:00Z")))
                    .as("Friday 02:00 (+02), the end").isEmpty();
        });
    }

    @Test
    void theReadOutsideACommandSeesTheCommittedRows() {
        assertThat(sessions.operatingDayAt(COMMITTED, cairo("2025-06-11T01:00")))
                .contains(LocalDate.parse("2025-06-10"));
        assertThat(sessions.operatingDayAt(COMMITTED, cairo("2025-06-11T02:00"))).isEmpty();
        assertThat(sessions.operatingDayAt(COMMITTED, cairo("2025-06-10T11:00"))).as("template hours, no row")
                .isEmpty();
    }

    @Test
    void theReadOutsideACommandDoesNotSeeAnUncommittedRow() {
        AtomicReference<Optional<LocalDate>> inside = new AtomicReference<>();
        inRolledBackCommand(ctx -> {
            session(ctx, VENUE, template(ctx, VENUE), "2026-11-05", "2026-11-05T18:00", "2026-11-06T02:00");
            inside.set(sessions.operatingDayAt(ctx, VENUE, cairo("2026-11-05T20:00")));
        });

        assertThat(inside.get()).as("visible on the command's own transaction")
                .contains(LocalDate.parse("2026-11-05"));
        assertThat(sessions.operatingDayAt(VENUE, cairo("2026-11-05T20:00"))).as("rolled back").isEmpty();
    }
}
