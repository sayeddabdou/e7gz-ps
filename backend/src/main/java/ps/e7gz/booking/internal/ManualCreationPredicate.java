package ps.e7gz.booking.internal;

import static ps.e7gz.booking.internal.ManualCreationCondition.ACTIVITY_SUPPORTED;
import static ps.e7gz.booking.internal.ManualCreationCondition.DURATION;
import static ps.e7gz.booking.internal.ManualCreationCondition.NO_BLOCKING_RESERVATION;
import static ps.e7gz.booking.internal.ManualCreationCondition.NO_CLOSURE;
import static ps.e7gz.booking.internal.ManualCreationCondition.NO_EMERGENCY_CLOSURE;
import static ps.e7gz.booking.internal.ManualCreationCondition.NO_MAINTENANCE;
import static ps.e7gz.booking.internal.ManualCreationCondition.NO_RESOURCE_SELLING_BLOCK;
import static ps.e7gz.booking.internal.ManualCreationCondition.NO_VENUE_SELLING_BLOCK;
import static ps.e7gz.booking.internal.ManualCreationCondition.PRICING_CONFIGURED;
import static ps.e7gz.booking.internal.ManualCreationCondition.RESOURCE_ACTIVE;
import static ps.e7gz.booking.internal.ManualCreationCondition.RESOURCE_NOT_UNDER_OWNER_REVIEW;
import static ps.e7gz.booking.internal.ManualCreationCondition.START_NOW_OR_FUTURE;
import static ps.e7gz.booking.internal.ManualCreationCondition.VENUE_ACCOUNT_ENABLED;
import static ps.e7gz.booking.internal.ManualCreationCondition.VENUE_NOT_ONBOARDING;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.money.Money;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.BusinessDates;
import ps.e7gz.pricing.api.HourlyPrices;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.SessionSnapshots;
import ps.e7gz.venue.api.SessionSnapshots.Session;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * Decides the fifteen {@link ManualCreationCondition}s of a new Manual Booking inside the creation command, after
 * {@code begin_command} has locked the Venue (with its account) and the Resource, and works out what the booking stores:
 * its status, interval, session snapshot and business date (eligibility category C, arch 06 §1.4). Separate from the
 * online predicate: no opening hours, no Area, no lead time, no customer (FR-MAN-01, FR-MAN-03, FR-MAN-07).
 *
 * <p>Every read goes through the owner module's api on the command's transaction; time is only
 * {@link CommandContext#commandNow()}. Conditions 3-6 and 8-12 are one call of the canonical availability predicate
 * ({@link AvailabilityQuery#isFree}, arch 01 §15 rule 1), never re-implemented here. The order of evaluation is not a
 * product rule (§30.2 requires all fifteen): the period shape (13-14) comes before the reads that need a valid period.
 * The database stays the authority at write time ({@code tr_22_booking_creation_venue},
 * {@code tr_20_booking_snapshot_integrity}, the reservation exclusion).
 */
@Component
class ManualCreationPredicate {

    /** §30.2 #14: 1 to 6 hours in 30-minute steps. */
    static final int MIN_MINUTES = 60;
    static final int MAX_MINUTES = 360;
    static final int STEP_MINUTES = 30;

    private final VenueEligibility venues;
    private final AvailabilityQuery availability;
    private final SessionSnapshots snapshots;
    private final HourlyPrices prices;
    private final ResourceCatalog resources;

    ManualCreationPredicate(VenueEligibility venues, AvailabilityQuery availability, SessionSnapshots snapshots,
                            HourlyPrices prices, ResourceCatalog resources) {
        this.venues = venues;
        this.availability = availability;
        this.snapshots = snapshots;
        this.prices = prices;
        this.resources = resources;
    }

    /**
     * All fifteen conditions pass, or the first failure found is thrown.
     *
     * @param resource the Resource the request names, of the signed-in venue (found before the command)
     * @throws ManualCreationRefused naming the failed condition and its public code
     */
    Approval check(CommandContext command, ManualBookingRequest request, ResourceCatalog.Entry resource) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(resource, "resource");
        Instant now = command.commandNow();

        // 1-2 - category C (FR-MAN-02): the account ENABLED; ACTIVE, INACTIVE or SUSPENDED, never ONBOARDING
        VenueEligibility.Eligibility venue = venues.find(command, resource.venueId())
                .orElseThrow(() -> new IllegalStateException("The Resource's Venue " + resource.venueId() + " vanished"));
        if (venue.accountStatus() != VenueAccountStatus.ENABLED) {
            // PO decision 10 Oct 2026, E7-48 (D4): the token filter refuses a disabled account first (401); this is
            // the race of an account disabled between the request and the command
            throw new ManualCreationRefused(VENUE_ACCOUNT_ENABLED);
        }
        if (venue.marketplaceStatus() == MarketplaceStatus.ONBOARDING) {
            throw new ManualCreationRefused(VENUE_NOT_ONBOARDING);
        }

        // 13 - NOW or FUTURE, never past (FR-MAN-04, FR-MAN-05). The database keeps microseconds; a start equal to the
        // command instant is a walk-in, ACTIVE (tr_22_booking_creation_venue: ACTIVE exactly when startAt = creation)
        Instant startAt = request.walkIn() ? now : request.startAt().truncatedTo(ChronoUnit.MICROS);
        if (startAt.isBefore(now)) {
            throw new ManualCreationRefused(START_NOW_OR_FUTURE);
        }
        BookingStatus status = startAt.equals(now) ? BookingStatus.ACTIVE : BookingStatus.CONFIRMED;
        // 14 - 1 to 6 hours in 30-minute steps; endAt is real elapsed time, the price uses the nominal minutes
        if (!durationValid(request.durationMinutes())) {
            throw new ManualCreationRefused(DURATION);
        }
        Instant endAt = startAt.plus(Duration.ofMinutes(request.durationMinutes()));

        // 7 - a play mode exactly for PlayStation (FR-VEN-42)
        if ((resource.activity() == Activity.PLAYSTATION) != (request.playMode() != null)) {
            throw new ManualCreationRefused(ACTIVITY_SUPPORTED);
        }

        // 3-6, 8-12 - one call of the canonical predicate; selling blocks are read now (D4)
        Optional<AvailabilityCheck> failing = availability.isFree(command, resource.resourceId(),
                new AvailabilityPeriod(startAt, endAt), now).failingCheck();
        if (failing.isPresent()) {
            throw new ManualCreationRefused(conditionOf(failing.get()));
        }

        // 15 - the price the activity needs (FR-MAN-10): nothing is created without it
        Money hourly = prices.resolve(command, resource.resourceId(), request.playMode())
                .orElseThrow(() -> new ManualCreationRefused(PRICING_CONFIGURED));
        // FR-MAN-08: no customer E7GZ fee, so the fee snapshot is 0 (ck_booking_manual_fee_zero)
        BookingAmounts amounts = BookingPricing.amounts(hourly, request.durationMinutes(), Money.ZERO);

        String resourceName = resources.name(command, resource.resourceId())
                .orElseThrow(() -> new IllegalStateException("Resource " + resource.resourceId() + " vanished"));
        Optional<Session> session = sessionContaining(command, resource, startAt, endAt);
        return new Approval(status, startAt, endAt, session.orElse(null), businessDate(session, startAt), amounts,
                resourceName);
    }

    /**
     * PO decision 10 Oct 2026, E7-48 (D1, D2), FR-MAN-09: the session snapshot only when the WHOLE interval fits one
     * published Operating Session - exactly what {@code tr_20_booking_snapshot_integrity} requires. A booking that
     * starts inside a session but ends after it has no snapshot.
     */
    private Optional<Session> sessionContaining(CommandContext command, ResourceCatalog.Entry resource, Instant startAt,
                                                Instant endAt) {
        // A current session only (v1.1.37): inside superseded history the walk-in is out of hours (FR-MAN-03)
        return snapshots.containing(command, resource.venueId(), startAt)
                .filter(session -> !session.end().isBefore(endAt));
    }

    /**
     * PO decision 10 Oct 2026, E7-48 (D1, D2), FR-MAN-09: the operating day inside a session, otherwise the Africa/Cairo
     * calendar date of {@code startAt} - always present. A 03:00 booking after a session ending 02:00 belongs to that
     * Saturday, not to the Friday the session opened on. Not {@link BusinessDates#businessDate}: its rule 1 looks at
     * {@code startAt} only, while a manual booking straddling a session's end has no snapshot.
     */
    static LocalDate businessDate(Optional<Session> session, Instant startAt) {
        return session.map(Session::operatingDay)
                .orElseGet(() -> LocalDate.ofInstant(startAt, BusinessDates.FALLBACK_ZONE));
    }

    static boolean durationValid(int minutes) {
        return minutes >= MIN_MINUTES && minutes <= MAX_MINUTES && minutes % STEP_MINUTES == 0;
    }

    /** Availability checks 1-8 (arch 05 §2.1) as manual conditions (see {@link ManualCreationCondition}). */
    static ManualCreationCondition conditionOf(AvailabilityCheck check) {
        return switch (check) {
            case VENUE_SELLING_BLOCK -> NO_VENUE_SELLING_BLOCK;
            case RESOURCE_SELLING_BLOCK -> NO_RESOURCE_SELLING_BLOCK;
            case RESOURCE_BASE_STATE -> RESOURCE_ACTIVE;
            case RESOURCE_OWNER_REVIEW -> RESOURCE_NOT_UNDER_OWNER_REVIEW;
            case EMERGENCY_INTERVAL -> NO_EMERGENCY_CLOSURE;
            case MAINTENANCE -> NO_MAINTENANCE;
            case CLOSURE -> NO_CLOSURE;
            case BLOCKING_RESERVATION -> NO_BLOCKING_RESERVATION;
        };
    }

    /**
     * What creation stores once every condition passed.
     *
     * @param status  ACTIVE for a walk-in starting at the command instant, CONFIRMED for a future start
     * @param session the Operating Session the whole interval fits in; {@code null} outside published hours
     */
    record Approval(BookingStatus status, Instant startAt, Instant endAt, Session session, LocalDate businessDate,
                    BookingAmounts amounts, String resourceName) {

        Approval {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(amounts, "amounts");
            Objects.requireNonNull(resourceName, "resourceName");
        }
    }
}
