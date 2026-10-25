package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aDefaultPrice;
import static ps.e7gz.support.fixtures.Fixtures.aPriceOverride;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.money.Money;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection.RebuildReport;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection.Row;
import ps.e7gz.inventory.availability.internal.NearestAvailabilityCalculator.Result;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.OccupiedRanges;
import ps.e7gz.inventory.ledger.api.OccupiedRanges.Occupied;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.operations.api.DeclaredEmergencyIntervals;
import ps.e7gz.operations.api.DeclaredEmergencyIntervals.Interval;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-33 - Nearest Availability and its projection end to end, over the real owner-module reads and the canonical
 * predicate on the migrated database, with data from the shared fixture builders (each test its own Venue).
 *
 * <p>Calculations at a fixed instant use sessions in February 2031 (Africa/Cairo is UTC+2 then): a session of 12:00-23:00
 * Cairo is {@code [10:00Z, 21:00Z)}. Refresh and rebuild run at the real command instant, which is before 2031, so the
 * first 2031 session is the first one ahead of the lead.
 */
class NearestAvailabilityIntegrationTest extends IntegrationTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final JdbcClient OWNER = client(MIGRATOR);

    @Autowired
    CommandRunner runner;

    @Autowired
    NearestAvailabilityCalculator calculator;

    @Autowired
    NearestAvailabilityProjection projection;

    @Autowired
    NearestAvailabilityProjectionService service;

    @Autowired
    ReservationLedger ledger;

    @Autowired
    OccupiedRanges occupiedRanges;

    @Autowired
    DeclaredEmergencyIntervals emergencies;

    @Autowired
    OperationalOverlaps operations;

    /** ReservationLedger.create publishes ReservationHeld, which carries the request's correlation ID (E7-46). */
    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    record TestCommand<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private <R> R run(Function<CommandContext, R> body) {
        return runner.run(new TestCommand<>(body));
    }

    private static JdbcClient client(String role) {
        return JdbcClient.create(new SimpleDriverDataSource(new Driver(), PostgresTestContainer.jdbcUrl(DATABASE),
                role, role));
    }

    private static Instant at(String utc) {
        return Instant.parse(utc);
    }

    // ---- data ---------------------------------------------------------------------------------------------------

    /**
     * An ACTIVE PlayStation venue with an ENABLED account (a Booking needs one) and the 12:00-23:00 Cairo sessions of 3
     * and 4 February 2031.
     */
    private static Fixtures.Venue venue() {
        Fixtures.Venue venue = aVenue().active().offering(Activity.PLAYSTATION).create();
        aVenueAccount().forVenue(venue).create();
        anOperatingSession().forVenue(venue).on("2031-02-03", "2031-02-03T12:00", "2031-02-03T23:00").create();
        anOperatingSession().forVenue(venue).on("2031-02-04", "2031-02-04T12:00", "2031-02-04T23:00").create();
        return venue;
    }

    private Result calculate(Fixtures.Venue venue, Activity activity, String now) {
        return run(ctx -> calculator.calculate(ctx, venue.id(), activity, at(now))).orElseThrow();
    }

    private Instant nearest(Fixtures.Venue venue, String now) {
        return calculate(venue, Activity.PLAYSTATION, now).nearestAvailableAt();
    }

    /** A CONFIRMED manual Booking out of hours: the owner of the test's BOOKING ledger rows. */
    private static UUID bookingOwner(Fixtures.Venue venue, Fixtures.Resource room) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date,
                                                     session_timezone, created_at)
                        VALUES (:id, :venue, :room, NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', :console, 'SINGLE',
                                :name, '2030-01-01 08:00:00+00', '2030-01-01 09:00:00+00', 60, 100.00, 100.00, 0.00,
                                100.00, DATE '2030-01-01', 'Africa/Cairo', now())""")
                .param("id", id).param("venue", venue.id()).param("room", room.id()).param("name", room.name())
                .param("console", room.consoleType().name())
                .update();
        return id;
    }

    /** An active BOOKING row on the room, through the ledger. */
    private void book(Fixtures.Venue venue, Fixtures.Resource room, Instant start, Instant end) {
        UUID booking = bookingOwner(venue, room);
        run(ctx -> ledger.create(ctx, new NewReservation(room.id(), ReservationOwner.booking(booking), start, end)));
    }

    private void book(Fixtures.Venue venue, Fixtures.Resource room, String start, String end) {
        book(venue, room, at(start), at(end));
    }

    /** Maintenance and, as arch 05 section 3.2 requires, its MAINTENANCE guard row. */
    private void maintenance(Fixtures.Resource room, String start, String end, boolean guardRow) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)
                        VALUES (:id, :room, tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                'E7-33 maintenance', false)""")
                .param("id", id).param("room", room.id())
                .param("start", OffsetDateTime.ofInstant(at(start), ZoneOffset.UTC))
                .param("end", OffsetDateTime.ofInstant(at(end), ZoneOffset.UTC)).update();
        if (guardRow) {
            run(ctx -> ledger.create(ctx, new NewReservation(room.id(), ReservationOwner.maintenance(id), at(start),
                    at(end))));
        }
    }

    private static void resourceSellingBlock(Fixtures.Resource room) {
        OWNER.sql("""
                        INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at)
                        VALUES (:id, :room, 'E7-33 test block', true, '2020-01-01 00:00:00+00')""")
                .param("id", UUID.randomUUID()).param("room", room.id()).update();
    }

    private static void venueSellingBlock(Fixtures.Venue venue) {
        OWNER.sql("""
                        INSERT INTO venue.venue_selling_block (id, venue_id, cause, active, started_at)
                        VALUES (:id, :venue, 'E7-33 test block', true, '2020-01-01 00:00:00+00')""")
                .param("id", UUID.randomUUID()).param("venue", venue.id()).update();
    }

    private static Instant databaseNow() {
        return OWNER.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    }

    private static Optional<Row> storedRow(UUID venueId, Activity activity) {
        return OWNER.sql("""
                        SELECT nearest_available_at, starting_price, refreshed_at
                          FROM inventory.venue_activity_availability WHERE venue_id = :venue AND activity = :activity""")
                .param("venue", venueId).param("activity", activity.name())
                .query((rs, n) -> {
                    OffsetDateTime nearest = rs.getObject("nearest_available_at", OffsetDateTime.class);
                    BigDecimal price = rs.getBigDecimal("starting_price");
                    return new Row(venueId, activity, nearest == null ? null : nearest.toInstant(),
                            price == null ? null : Money.of(price),
                            rs.getObject("refreshed_at", OffsetDateTime.class).toInstant());
                })
                .optional();
    }

    // ---- eligibility (FR-DIS-05) ----------------------------------------------------------------------------------

    @Test
    void withNoEligibleResourceThereIsNoNearestAvailability() {
        Fixtures.Venue venue = venue();
        aResource().at(venue).disabled().create();
        aResource().at(venue).archived().create();
        aDefaultPrice().forVenue(venue).create();

        assertThat(calculate(venue, Activity.PLAYSTATION, "2031-02-03T08:00:00Z")).isEqualTo(Result.NONE);
    }

    @Test
    void disabledArchivedOwnerReviewAndSellingBlockedRoomsNeverContribute() {
        Fixtures.Venue venue = venue();
        Fixtures.Resource active = aResource().at(venue).create();
        book(venue, active, "2031-02-03T10:00:00Z", "2031-02-03T14:00:00Z");
        aResource().at(venue).disabled().create();
        aResource().at(venue).archived().create();
        aResource().at(venue).ownerReviewRequired().create();
        resourceSellingBlock(aResource().at(venue).create());

        assertThat(nearest(venue, "2031-02-03T08:00:00Z"))
                .as("the four free but ineligible rooms would give 10:00; the only eligible one is free at 14:00")
                .isEqualTo(at("2031-02-03T14:00:00Z"));
    }

    @Test
    void aSellingBlockedVenueHasNoNearestAvailabilityAndNoStartingPrice() {
        Fixtures.Venue venue = venue();
        aResource().at(venue).create();
        aDefaultPrice().forVenue(venue).create();
        venueSellingBlock(venue);

        assertThat(calculate(venue, Activity.PLAYSTATION, "2031-02-03T08:00:00Z")).isEqualTo(Result.NONE);
    }

    // ---- lead, sessions, the 60-minute window ---------------------------------------------------------------------

    @Test
    void theFifteenMinuteLeadGovernsInsideASession() {
        Fixtures.Venue venue = venue();
        aResource().at(venue).create();

        assertThat(nearest(venue, "2031-02-03T11:00:00Z")).isEqualTo(at("2031-02-03T11:15:00Z"));
    }

    @Test
    void aSessionStartingAfterTheLeadGoverns() {
        Fixtures.Venue venue = venue();
        aResource().at(venue).create();

        assertThat(nearest(venue, "2031-02-03T08:00:00Z")).isEqualTo(at("2031-02-03T10:00:00Z"));
    }

    @Test
    void aSessionWithLessThanSixtyMinutesLeftIsRejected() {
        Fixtures.Venue venue = venue();
        aResource().at(venue).create();

        assertThat(nearest(venue, "2031-02-03T19:45:01Z")).as("19:45:01 + 15 min leaves 59:59 before 21:00")
                .isEqualTo(at("2031-02-04T10:00:00Z"));
        assertThat(nearest(venue, "2031-02-03T19:45:00Z")).isEqualTo(at("2031-02-03T20:00:00Z"));
    }

    @Test
    void anExactSixtyMinuteGapIsAcceptedAndAShorterOneIsNot() {
        Fixtures.Venue venue = venue();
        Fixtures.Resource exact = aResource().at(venue).create();
        book(venue, exact, "2031-02-03T10:00:00Z", "2031-02-03T11:00:00Z");
        book(venue, exact, "2031-02-03T12:00:00Z", "2031-02-03T21:00:00Z");
        assertThat(nearest(venue, "2031-02-03T08:00:00Z")).isEqualTo(at("2031-02-03T11:00:00Z"));

        Fixtures.Venue other = venue();
        Fixtures.Resource tooShort = aResource().at(other).create();
        book(other, tooShort, "2031-02-03T10:00:00Z", "2031-02-03T11:00:00Z");
        book(other, tooShort, "2031-02-03T11:59:00Z", "2031-02-03T13:00:00Z");
        assertThat(nearest(other, "2031-02-03T08:00:00Z")).isEqualTo(at("2031-02-03T13:00:00Z"));
    }

    // ---- occupied ranges -------------------------------------------------------------------------------------------

    @Test
    void overlappingAndAdjacentOccupiedRangesAreMergedByTheLedger() {
        Fixtures.Venue venue = venue();
        Fixtures.Resource room = aResource().at(venue).create();
        maintenance(room, "2031-02-03T10:00:00Z", "2031-02-03T11:30:00Z", true);
        maintenance(room, "2031-02-03T11:00:00Z", "2031-02-03T12:00:00Z", true);     // overlaps the first
        book(venue, room, "2031-02-03T12:00:00Z", "2031-02-03T13:00:00Z");             // back-to-back
        book(venue, room, "2031-02-03T13:30:00Z", "2031-02-03T14:00:00Z");             // a 30-minute gap before it

        List<Occupied> merged = run(ctx -> occupiedRanges.occupied(ctx, List.of(room.id()),
                at("2031-02-03T10:00:00Z"), at("2031-02-03T21:00:00Z")));
        assertThat(merged).containsExactly(
                new Occupied(room.id(), at("2031-02-03T10:00:00Z"), at("2031-02-03T13:00:00Z")),
                new Occupied(room.id(), at("2031-02-03T13:30:00Z"), at("2031-02-03T14:00:00Z")));

        assertThat(nearest(venue, "2031-02-03T08:00:00Z")).isEqualTo(at("2031-02-03T14:00:00Z"));
    }

    @Test
    void aReleasedReservationIsNotOccupied() {
        Fixtures.Venue venue = venue();
        Fixtures.Resource room = aResource().at(venue).create();
        UUID booking = bookingOwner(venue, room);
        UUID row = run(ctx -> ledger.create(ctx, new NewReservation(room.id(), ReservationOwner.booking(booking),
                at("2031-02-03T10:00:00Z"), at("2031-02-03T12:00:00Z"))));
        assertThat(nearest(venue, "2031-02-03T08:00:00Z")).isEqualTo(at("2031-02-03T12:00:00Z"));

        run(ctx -> ledger.release(ctx, row, ctx.commandNow(), "E7-33 test: cancelled"));

        assertThat(nearest(venue, "2031-02-03T08:00:00Z")).isEqualTo(at("2031-02-03T10:00:00Z"));
    }

    @Test
    void theCandidateIsTheFreeGapBoundaryRoundedUpToTheGridNotAMinuteStep() {
        // The boundary is the exact busy end; the 15-minute grid (PO decision 12 Oct 2026, E7-115) then rounds it up.
        Fixtures.Venue venue = venue();
        Fixtures.Resource room = aResource().at(venue).create();
        Instant oddEnd = at("2031-02-03T10:07:13.500Z");
        book(venue, room, at("2031-02-03T10:00:00Z"), oddEnd);

        assertThat(nearest(venue, "2031-02-03T08:00:00Z")).isEqualTo(at("2031-02-03T10:15:00Z"));
    }

    @Test
    void theGloballyEarliestCandidateAcrossEligibleRoomsWins() {
        Fixtures.Venue venue = venue();
        Fixtures.Resource late = aResource().at(venue).create();
        Fixtures.Resource early = aResource().at(venue).playStation(ConsoleType.PS4).create();
        book(venue, late, "2031-02-03T10:00:00Z", "2031-02-03T14:00:00Z");
        book(venue, early, "2031-02-03T10:00:00Z", "2031-02-03T12:30:00Z");

        assertThat(nearest(venue, "2031-02-03T08:00:00Z")).isEqualTo(at("2031-02-03T12:30:00Z"));
    }

    @Test
    void theCanonicalPredicateHasTheLastWordOverTheBoundaries() {
        // Maintenance without its guard row (which arch 05 section 3.2 always writes): the ledger shows nothing, the
        // boundaries offer 10:00, and isFree refuses it at check 6. The room then gives nothing in that session.
        Fixtures.Venue venue = venue();
        Fixtures.Resource room = aResource().at(venue).create();
        maintenance(room, "2031-02-03T10:00:00Z", "2031-02-03T11:00:00Z", false);

        assertThat(nearest(venue, "2031-02-03T08:00:00Z")).isEqualTo(at("2031-02-04T10:00:00Z"));
    }

    @Test
    void aDeclaredEmergencyIntervalEndIsTheNextCandidate() {
        // The database declares an Emergency at its own now() inside a session, together with its selling block.
        // The calculation runs one second before the declaration (as E7-30's check 5 test does), so the block is not
        // yet in force and only the declared interval stands in the way.
        Fixtures.Venue venue = aVenue().active().offering(Activity.PLAYSTATION).create();
        Fixtures.Resource room = aResource().at(venue).create();
        LocalDateTime sessionStart = LocalDateTime.ofInstant(databaseNow(), CAIRO).truncatedTo(ChronoUnit.HOURS)
                .minusHours(2);
        anOperatingSession().forVenue(venue).on(sessionStart.toLocalDate().toString(), sessionStart.toString(),
                sessionStart.plusHours(22).toString()).create();
        OffsetDateTime[] declared = OWNER.sql("""
                        WITH e AS (
                            INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                              expected_end_at, declared_period, reason)
                            VALUES (:emergency, 'RESOURCE', :venue, :room, 'ACTIVE', now(), now() + interval '1 hour',
                                    tstzrange(now(), now() + interval '1 hour', '[)'), 'E7-33 emergency')
                            RETURNING id, declared_at, expected_end_at)
                        INSERT INTO resource.resource_selling_block (id, resource_id, cause, emergency_id, active,
                                                                     started_at)
                        SELECT :block, :room, 'EMERGENCY', e.id, true, e.declared_at FROM e
                        RETURNING (SELECT declared_at FROM e), (SELECT expected_end_at FROM e)""")
                .param("emergency", UUID.randomUUID()).param("block", UUID.randomUUID())
                .param("venue", venue.id()).param("room", room.id())
                .query((rs, n) -> new OffsetDateTime[]{rs.getObject(1, OffsetDateTime.class),
                        rs.getObject(2, OffsetDateTime.class)})
                .single();
        Instant declaredAt = declared[0].toInstant();
        Instant expectedEnd = declared[1].toInstant();

        Fixtures.Resource otherRoom = aResource().at(venue).disabled().create();      // never contributes itself
        Instant windowEnd = expectedEnd.plusSeconds(3600);
        List<Interval> onRoom = run(ctx -> emergencies.blocking(ctx, venue.id(), room.id(), declaredAt, windowEnd));
        boolean check5OnRoom = run(ctx -> operations.declaredEmergencyOverlaps(ctx, venue.id(), room.id(),
                declaredAt, windowEnd));
        List<Interval> onOtherRoom = run(ctx -> emergencies.blocking(ctx, venue.id(), otherRoom.id(), declaredAt,
                windowEnd));
        boolean check5OnOtherRoom = run(ctx -> operations.declaredEmergencyOverlaps(ctx, venue.id(), otherRoom.id(),
                declaredAt, windowEnd));
        assertThat(onRoom).as("the interval check 5 counts").containsExactly(new Interval(declaredAt, expectedEnd));
        assertThat(check5OnRoom).isTrue();
        assertThat(onOtherRoom).as("a RESOURCE Emergency does not count against another room").isEmpty();
        assertThat(check5OnOtherRoom).isFalse();

        Result result = run(ctx -> calculator.calculate(ctx, venue.id(), Activity.PLAYSTATION,
                declaredAt.minusSeconds(1))).orElseThrow();

        assertThat(result.nearestAvailableAt()).as("the interval's end, on the 15-minute grid (E7-115)")
                .isEqualTo(StartGrid.ceil(expectedEnd));
    }

    // ---- starting price (PO decision 7 Oct 2026) ----------------------------------------------------------------

    @Test
    void theStartingPriceIsTheLowestCanonicalHourlyPriceOfEligibleRoomsOverBothPlayModes() {
        Fixtures.Venue venue = venue();
        Fixtures.Resource ps5 = aResource().at(venue).create();
        aResource().at(venue).playStation(ConsoleType.PS4).create();               // no PS4 price: skipped
        Fixtures.Resource disabled = aResource().at(venue).disabled().create();
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.SINGLE).hourly("60.00").create();
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.MULTIPLAYER).hourly("90.00").create();
        aPriceOverride().forRoom(disabled).playMode(PlayMode.SINGLE).hourly("10.00").create();
        assertThat(calculate(venue, Activity.PLAYSTATION, "2031-02-03T08:00:00Z").startingPrice())
                .isEqualTo(Money.of("60.00"));

        aPriceOverride().forRoom(ps5).playMode(PlayMode.MULTIPLAYER).hourly("55.00").create();
        assertThat(calculate(venue, Activity.PLAYSTATION, "2031-02-03T08:00:00Z").startingPrice())
                .as("the room override wins over the default for that mode, and is now the lowest")
                .isEqualTo(Money.of("55.00"));
    }

    @Test
    void billiardsUsesTheVenueRateAndNoPriceLeavesTheStartingPriceEmpty() {
        Fixtures.Venue venue = aVenue().active().offering(Activity.BILLIARDS).create();
        anOperatingSession().forVenue(venue).on("2031-02-03", "2031-02-03T12:00", "2031-02-03T23:00").create();
        aResource().at(venue).billiards().create();

        Result unpriced = calculate(venue, Activity.BILLIARDS, "2031-02-03T08:00:00Z");
        assertThat(unpriced.startingPrice()).isNull();
        assertThat(unpriced.nearestAvailableAt()).isEqualTo(at("2031-02-03T10:00:00Z"));

        aDefaultPrice().forVenue(venue).billiards().hourly("50.00").create();
        assertThat(calculate(venue, Activity.BILLIARDS, "2031-02-03T08:00:00Z").startingPrice())
                .isEqualTo(Money.of("50.00"));
    }

    // ---- refresh -------------------------------------------------------------------------------------------------

    @Test
    void refreshWritesTheRowAtTheCommandInstantAndFollowsAReservation() {
        Fixtures.Venue venue = venue();
        Fixtures.Resource room = aResource().at(venue).create();
        aDefaultPrice().forVenue(venue).create();

        Instant before = databaseNow();
        Row first = projection.refresh(venue.id(), Activity.PLAYSTATION).orElseThrow();
        Instant after = databaseNow();

        assertThat(first.nearestAvailableAt()).isEqualTo(at("2031-02-03T10:00:00Z"));
        assertThat(first.startingPrice()).isEqualTo(Money.of("50.00"));
        assertThat(first.refreshedAt()).as("the database command instant").isBetween(before, after);
        assertThat(storedRow(venue.id(), Activity.PLAYSTATION)).contains(first);

        book(venue, room, "2031-02-03T10:00:00Z", "2031-02-03T11:30:00Z");
        Row second = projection.refresh(venue.id(), Activity.PLAYSTATION).orElseThrow();

        assertThat(second.nearestAvailableAt()).isEqualTo(at("2031-02-03T11:30:00Z"));
        assertThat(second.refreshedAt()).isAfterOrEqualTo(first.refreshedAt());
    }

    @Test
    void refreshFollowsAPricingChange() {
        Fixtures.Venue venue = venue();
        Fixtures.Resource room = aResource().at(venue).create();
        aDefaultPrice().forVenue(venue).hourly("50.00").create();
        assertThat(projection.refresh(venue.id(), Activity.PLAYSTATION).orElseThrow().startingPrice())
                .isEqualTo(Money.of("50.00"));

        aPriceOverride().forRoom(room).hourly("45.00").create();

        assertThat(projection.refresh(venue.id(), Activity.PLAYSTATION).orElseThrow().startingPrice())
                .isEqualTo(Money.of("45.00"));
    }

    @Test
    void refreshWithoutAnyResourceRemovesTheRowInsteadOfWritingNulls() {
        Fixtures.Venue venue = aVenue().active().offering(Activity.PING_PONG).create();
        OWNER.sql("""
                        INSERT INTO inventory.venue_activity_availability (venue_id, activity, refreshed_at)
                        VALUES (:venue, 'PING_PONG', '2020-01-01 00:00:00+00')""")
                .param("venue", venue.id()).update();

        assertThat(projection.refresh(venue.id(), Activity.PING_PONG)).isEmpty();
        assertThat(storedRow(venue.id(), Activity.PING_PONG)).isEmpty();
        assertThat(projection.refresh(venue.id(), Activity.PING_PONG)).as("and none is created").isEmpty();
        assertThat(storedRow(venue.id(), Activity.PING_PONG)).isEmpty();
    }

    @Test
    void aPairWithOnlyIneligibleResourcesKeepsARowWithoutValues() {
        Fixtures.Venue venue = venue();
        aResource().at(venue).archived().create();

        Row row = projection.refresh(venue.id(), Activity.PLAYSTATION).orElseThrow();

        assertThat(row.nearestAvailableAt()).isNull();
        assertThat(row.startingPrice()).isNull();
        assertThat(storedRow(venue.id(), Activity.PLAYSTATION)).contains(row);
    }

    @Test
    void aRefreshNeverOverwritesARowRefreshedLater() {
        Fixtures.Venue venue = venue();
        aResource().at(venue).create();
        OWNER.sql("""
                        INSERT INTO inventory.venue_activity_availability (venue_id, activity, nearest_available_at,
                                                                           refreshed_at)
                        VALUES (:venue, 'PLAYSTATION', '2040-01-01 00:00:00+00', '2040-01-01 00:00:00+00')""")
                .param("venue", venue.id()).update();

        Row row = projection.refresh(venue.id(), Activity.PLAYSTATION).orElseThrow();

        assertThat(row.refreshedAt()).isEqualTo(at("2040-01-01T00:00:00Z"));
        assertThat(row.nearestAvailableAt()).isEqualTo(at("2040-01-01T00:00:00Z"));
    }

    // ---- rebuild -------------------------------------------------------------------------------------------------

    @Test
    void rebuildEqualsAFreshCalculationOnSeededDataAndIsSafeToRerun() {
        // Seeded: a busy priced PlayStation venue, a selling-blocked one, one with only ineligible rooms, a billiards
        // venue without a price, and a pair without Resources holding a stale row.
        Fixtures.Venue busy = venue();
        Fixtures.Resource busyRoom = aResource().at(busy).create();
        aResource().at(busy).playStation(ConsoleType.PS4).ownerReviewRequired().create();
        book(busy, busyRoom, "2031-02-03T10:00:00Z", "2031-02-03T15:00:00Z");
        aDefaultPrice().forVenue(busy).hourly("70.00").create();
        aDefaultPrice().forVenue(busy).playStation(ConsoleType.PS5, PlayMode.MULTIPLAYER).hourly("65.00").create();

        Fixtures.Venue blocked = venue();
        aResource().at(blocked).create();
        aDefaultPrice().forVenue(blocked).create();
        venueSellingBlock(blocked);

        Fixtures.Venue ineligible = venue();
        aResource().at(ineligible).disabled().create();

        Fixtures.Venue billiards = aVenue().active().offering(Activity.BILLIARDS, Activity.PING_PONG).create();
        anOperatingSession().forVenue(billiards).on("2031-02-03", "2031-02-03T12:00", "2031-02-03T23:00").create();
        aResource().at(billiards).billiards().create();
        OWNER.sql("""
                        INSERT INTO inventory.venue_activity_availability (venue_id, activity, refreshed_at)
                        VALUES (:venue, 'PING_PONG', '2020-01-01 00:00:00+00')""")
                .param("venue", billiards.id()).update();

        RebuildReport first = projection.rebuild();

        // From-scratch expectations, worked out by hand from the seeded rows.
        assertThat(storedRow(busy.id(), Activity.PLAYSTATION)).get()
                .extracting(Row::nearestAvailableAt, Row::startingPrice)
                .containsExactly(at("2031-02-03T15:00:00Z"), Money.of("65.00"));
        assertThat(storedRow(blocked.id(), Activity.PLAYSTATION)).get()
                .extracting(Row::nearestAvailableAt, Row::startingPrice).containsExactly(null, null);
        assertThat(storedRow(ineligible.id(), Activity.PLAYSTATION)).get()
                .extracting(Row::nearestAvailableAt, Row::startingPrice).containsExactly(null, null);
        assertThat(storedRow(billiards.id(), Activity.BILLIARDS)).get()
                .extracting(Row::nearestAvailableAt, Row::startingPrice)
                .containsExactly(at("2031-02-03T10:00:00Z"), null);
        assertThat(storedRow(billiards.id(), Activity.PING_PONG)).as("stale row without a Resource removed").isEmpty();

        // Over the whole database (every test's data): each row equals a fresh calculation at its own instant, and
        // every pair with a Resource has exactly one row.
        List<Row> stored = everyStoredRowEqualsAFreshCalculation();
        long pairsWithResources = OWNER.sql("SELECT count(DISTINCT (venue_id, activity)) FROM resource.resource")
                .query(Long.class).single();
        assertThat((long) stored.size()).isEqualTo(pairsWithResources);
        assertThat((long) first.written()).isEqualTo(pairsWithResources);
        assertThat(first.pairs() - first.written()).isEqualTo(first.withoutResources());

        // Safe to rerun: the same pairs, no duplicate or stale rows, each row a fresh calculation at the second run's
        // own instant, and an unchanged starting price. nearest_available_at may advance with the database clock
        // (a pair whose session is running now is lead-governed); the seeded 2031 rows do not depend on it.
        RebuildReport second = projection.rebuild();
        assertThat(second).isEqualTo(first);
        List<Row> again = everyStoredRowEqualsAFreshCalculation();
        assertThat(again).hasSameSizeAs(stored);
        assertThat(storedRow(billiards.id(), Activity.PING_PONG)).as("the stale row stays removed").isEmpty();
        for (Row before : stored) {
            Row after = storedRow(before.venueId(), before.activity()).orElseThrow();
            assertThat(after.refreshedAt()).isAfterOrEqualTo(before.refreshedAt());
            assertThat(after.startingPrice()).isEqualTo(before.startingPrice());
        }
        for (UUID seeded : List.of(busy.id(), blocked.id(), ineligible.id(), billiards.id())) {
            for (Row before : stored.stream().filter(row -> row.venueId().equals(seeded)).toList()) {
                assertThat(storedRow(seeded, before.activity()).orElseThrow().nearestAvailableAt())
                        .as("time-independent row of the seeded venue " + seeded)
                        .isEqualTo(before.nearestAvailableAt());
            }
        }
    }

    private List<Row> everyStoredRowEqualsAFreshCalculation() {
        List<Row> stored = OWNER.sql("SELECT venue_id, activity FROM inventory.venue_activity_availability")
                .query((rs, n) -> storedRow(rs.getObject("venue_id", UUID.class),
                        Activity.valueOf(rs.getString("activity"))).orElseThrow())
                .list();
        for (Row row : stored) {
            Result fresh = run(ctx -> calculator.calculate(ctx, row.venueId(), row.activity(), row.refreshedAt()))
                    .orElseThrow();
            assertThat(new Result(row.nearestAvailableAt(), row.startingPrice()))
                    .as(row.venueId() + " " + row.activity()).isEqualTo(fresh);
        }
        return stored;
    }

    // ---- worker pass (Sprint 1 review, PO decision 8 Oct 2026) --------------------------------------------------

    @Test
    void theWorkerPassRefreshesAStaleRowWithoutAnyEvent() {
        Fixtures.Venue venue = venue();
        aResource().at(venue).create();
        OWNER.sql("""
                        INSERT INTO inventory.venue_activity_availability (venue_id, activity, nearest_available_at,
                                                                           refreshed_at)
                        VALUES (:venue, 'PLAYSTATION', '2020-01-01 10:00:00+00', '2020-01-01 00:00:00+00')""")
                .param("venue", venue.id()).update();

        new NearestAvailabilityJobs(service).refreshEveryPair();     // the worker profile's timer, run by hand

        Row row = storedRow(venue.id(), Activity.PLAYSTATION).orElseThrow();
        assertThat(row.nearestAvailableAt()).as("the first 2031 session, not the stale 2020 start")
                .isEqualTo(at("2031-02-03T10:00:00Z"));
        assertThat(row.refreshedAt()).isAfter(at("2020-01-01T00:00:00Z"));
    }

    @Test
    void theProjectionIsNeverTheOnlyThingThatKeepsAnActivityInUse() {
        Fixtures.Venue venue = aVenue().active().offering(Activity.PLAYSTATION, Activity.BILLIARDS).create();
        aResource().at(venue).create();

        projection.rebuild();

        assertThat(storedRow(venue.id(), Activity.BILLIARDS)).isEmpty();
        int removed = client(APP_RUNTIME).sql("""
                        DELETE FROM venue.venue_activity WHERE venue_id = :venue AND activity = 'BILLIARDS'""")
                .param("venue", venue.id()).update();
        assertThat(removed).as("fk_venue_activity_availability_venue_activity does not refuse it").isEqualTo(1);
    }
}
