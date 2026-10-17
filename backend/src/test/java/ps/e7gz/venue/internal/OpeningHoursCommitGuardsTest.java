package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.api.HoursChangeBookings;
import ps.e7gz.venue.api.HoursChangeBookings.BookingFacts;

/**
 * E7-113 Phase 4: the two checks {@link OpeningHoursCommands.Commit} makes under its locks after the token matched -
 * that the set it recomputed is covered by the locks it was given (FR-VEN-26, arch 04 §2.1), and that a set which is
 * protected now needs the Owner Password even though the caller planned an unprotected commit (FR-VEN-25). Both
 * orders are races a caller cannot time from outside, so the command is run directly with a Booking port that reports
 * one booking as a CONFIRMED Online Booking of a real Customer (its status may have changed under the venue lock) and
 * records any cancellation. Each refusal must write nothing: no cancellation call, the hours and sessions unchanged.
 */
class OpeningHoursCommitGuardsTest extends IntegrationTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    CommandRunner runner;

    @Autowired
    ReservationLedger ledger;

    @Autowired
    ActiveCommitments commitments;

    @Autowired
    HoursChangeBookings bookings;

    @Autowired
    OperatingSessionProperties properties;

    private CorrelationId.Scope correlation;
    private UUID venue;
    private UUID account;
    private UUID resource;
    private UUID customer;
    private UUID booking;
    private ReportingConfirmed port;
    private OpeningHoursCommands.Readers readers;
    private OpeningHours later;

    @BeforeEach
    void aSavedVenueWithOneBookingTheNewHoursNoLongerCover() {
        correlation = CorrelationId.open(CorrelationId.generate());
        Fixtures.Venue v = aVenue().active().create();
        venue = v.id();
        account = aVenueAccount().forVenue(v).create().id();
        resource = aResource().at(v).playStation(ConsoleType.PS5).create().id();
        customer = aUser().verified().create().id();
        OpeningHoursCommands.Readers real = new OpeningHoursCommands.Readers(commitments, bookings,
                properties.horizonDays());
        OpeningHours early = OpeningHours.validated(LocalTime.of(12, 0), LocalTime.of(23, 0), false, List.of());
        commit(early, LockSet.builder().venues(venue).build(), real);

        LocalDate day = LocalDate.ofInstant(OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single()
                .toInstant(), CAIRO).plusDays(10);
        booking = manualBookingAt(day, LocalDateTime.of(day, LocalTime.of(13, 0)));
        port = new ReportingConfirmed(bookings, booking, customer);
        readers = new OpeningHoursCommands.Readers(commitments, port, properties.horizonDays());
        later = OpeningHours.validated(LocalTime.of(15, 0), LocalTime.of(23, 0), false, List.of());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void aSetThatIsProtectedUnderTheLocksNeedsTheOwnerPasswordAndNothingIsWritten() {
        OpeningHoursCommands.Impact impact = preview();
        assertThat(impact.affected()).singleElement().satisfies(row -> {
            assertThat(row.bookingId()).isEqualTo(booking);
            assertThat(row.customerId()).isEqualTo(customer);
            assertThat(row.protectedCancellation()).isTrue();
        });
        List<String> sessionsBefore = sessions();

        assertThatThrownBy(() -> runner.run(new OpeningHoursCommands.Commit(venue, account, later,
                impact.previewToken(), impact.lockSet(venue), readers)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.OWNER_PASSWORD_REQUIRED));

        assertThat(port.cancelled).as("no booking was cancelled").isEmpty();
        assertNothingChanged(sessionsBefore);
    }

    @Test
    void aSetWhoseCustomerOrResourceIsNotInTheLockSetIsRefusedAsChangedAndNothingIsWritten() {
        OpeningHoursCommands.Impact impact = preview();
        assertThat(impact.lockSet(venue).users()).containsExactly(customer);
        assertThat(impact.lockSet(venue).resources()).containsExactly(resource);
        List<String> sessionsBefore = sessions();

        for (LockSet incomplete : List.of(LockSet.builder().venues(venue).resources(resource).build(),
                LockSet.builder().venues(venue).users(customer).build())) {
            assertThatThrownBy(() -> runner.run(new OpeningHoursCommands.Commit(venue, account, later,
                    impact.previewToken(), incomplete, readers)))
                    .as("locks %s", incomplete)
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.code()).isEqualTo(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW));
        }

        assertThat(port.cancelled).isEmpty();
        assertNothingChanged(sessionsBefore);
    }

    @Test
    void anotherVenuesAccountIsNotFoundAndNothingIsWritten() {
        OpeningHoursCommands.Impact impact = preview();
        Fixtures.Venue other = aVenue().active().create();
        UUID otherAccount = aVenueAccount().forVenue(other).create().id();
        List<String> sessionsBefore = sessions();

        assertThatThrownBy(() -> runner.run(new OpeningHoursCommands.Commit(venue, otherAccount, later,
                impact.previewToken(), impact.lockSet(venue), readers)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));

        assertThat(port.cancelled).isEmpty();
        assertNothingChanged(sessionsBefore);
    }

    // ---------------------------------------------------------------- helpers

    private OpeningHoursCommands.Impact preview() {
        return runner.run(new OpeningHoursCommands.Preview(venue, later, readers));
    }

    private void commit(OpeningHours hours, LockSet locks, OpeningHoursCommands.Readers with) {
        String token = runner.run(new OpeningHoursCommands.Preview(venue, hours, with)).previewToken();
        runner.run(new OpeningHoursCommands.Commit(venue, account, hours, token, locks, with));
    }

    private void assertNothingChanged(List<String> sessionsBefore) {
        assertThat(OWNER.sql("SELECT version FROM venue.venue_opening_template WHERE venue_id = :v")
                .param("v", venue).query(Long.class).single()).isEqualTo(1);
        assertThat(sessions()).isEqualTo(sessionsBefore);
        assertThat(OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", booking)
                .query(String.class).single()).isEqualTo("CONFIRMED");
    }

    private List<String> sessions() {
        return OWNER.sql("""
                        SELECT id || ' ' || operating_day || ' ' || generated_from_version FROM venue.operating_session
                         WHERE venue_id = :v ORDER BY operating_day, id""")
                .param("v", venue).query(String.class).list();
    }

    /** A CONFIRMED Manual Booking row in the day's session, with its BOOKING reservation (as E7-25's test makes it). */
    private UUID manualBookingAt(LocalDate day, LocalDateTime start) {
        Map<String, Object> session = OWNER.sql("""
                        SELECT id, local_start, local_end FROM venue.operating_session
                         WHERE venue_id = :v AND operating_day = :d""")
                .param("v", venue).param("d", day).query().singleRow();
        UUID id = UUID.randomUUID();
        Instant from = start.atZone(CAIRO).toInstant();
        Instant to = start.plusHours(1).atZone(CAIRO).toInstant();
        OWNER.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date, operating_day,
                                                     operating_session_id, session_local_start, session_local_end,
                                                     session_timezone, created_at)
                        VALUES (:id, :venue, :resource, NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
                                :name, :start, :end, 60, 100.00, 100.00, 0.00, 100.00, :day, :day, :session,
                                :localStart, :localEnd, 'Africa/Cairo', now())""")
                .param("id", id).param("venue", venue).param("resource", resource)
                .param("name", OWNER.sql("SELECT name FROM resource.resource WHERE id = :id").param("id", resource)
                        .query(String.class).single())
                .param("start", OffsetDateTime.ofInstant(from, CAIRO)).param("end", OffsetDateTime.ofInstant(to, CAIRO))
                .param("day", day).param("session", session.get("id"))
                .param("localStart", session.get("local_start")).param("localEnd", session.get("local_end")).update();
        runner.run(new Body<>(LockSet.builder().venues(venue).resources(resource).build(), ctx -> ledger.create(ctx,
                new NewReservation(resource, ReservationOwner.booking(id), from, to))));
        return id;
    }

    /** The real port, except that it reports {@code booking} as a CONFIRMED Online Booking of {@code customer}. */
    private static final class ReportingConfirmed implements HoursChangeBookings {

        private final HoursChangeBookings real;
        private final UUID booking;
        private final UUID customer;
        final List<List<UUID>> cancelled = new ArrayList<>();

        ReportingConfirmed(HoursChangeBookings real, UUID booking, UUID customer) {
            this.real = real;
            this.booking = booking;
            this.customer = customer;
        }

        @Override
        public Map<UUID, BookingFacts> facts(CommandContext command, Collection<UUID> bookingIds) {
            Map<UUID, BookingFacts> facts = new java.util.HashMap<>(real.facts(command, bookingIds));
            facts.computeIfPresent(booking, (id, f) -> new BookingFacts(id, BookingStatus.CONFIRMED,
                    BookingSource.ONLINE, customer, f.insideOperatingSession(), false));
            return facts;
        }

        @Override
        public void cancel(CommandContext command, UUID venueId, UUID venueAccountId, List<UUID> bookingIds) {
            cancelled.add(List.copyOf(bookingIds));
            throw new AssertionError("no cancellation may run: " + bookingIds);
        }
    }

    private record Body<R>(LockSet locks, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
