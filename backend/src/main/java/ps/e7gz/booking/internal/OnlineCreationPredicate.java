package ps.e7gz.booking.internal;

import static ps.e7gz.booking.internal.OnlineCreationCondition.ACTIVITY_COMPATIBLE;
import static ps.e7gz.booking.internal.OnlineCreationCondition.AREA_ACTIVE;
import static ps.e7gz.booking.internal.OnlineCreationCondition.BEFORE_INTAKE_CUTOFF;
import static ps.e7gz.booking.internal.OnlineCreationCondition.BELOW_COMMITMENT_LIMIT;
import static ps.e7gz.booking.internal.OnlineCreationCondition.DURATION_INCREMENT;
import static ps.e7gz.booking.internal.OnlineCreationCondition.DURATION_RANGE;
import static ps.e7gz.booking.internal.OnlineCreationCondition.LEAD_TIME;
import static ps.e7gz.booking.internal.OnlineCreationCondition.NOT_BANNED;
import static ps.e7gz.booking.internal.OnlineCreationCondition.NO_BLOCKING_RESERVATION;
import static ps.e7gz.booking.internal.OnlineCreationCondition.NO_OVERLAPPING_COMMITMENT;
import static ps.e7gz.booking.internal.OnlineCreationCondition.NO_RESOURCE_SELLING_BLOCK;
import static ps.e7gz.booking.internal.OnlineCreationCondition.NO_VENUE_SELLING_BLOCK;
import static ps.e7gz.booking.internal.OnlineCreationCondition.OTHER_PRODUCT_VALIDATION;
import static ps.e7gz.booking.internal.OnlineCreationCondition.PHONE_VERIFIED;
import static ps.e7gz.booking.internal.OnlineCreationCondition.RESOURCE_ACTIVE;
import static ps.e7gz.booking.internal.OnlineCreationCondition.RESOURCE_NOT_UNDER_OWNER_REVIEW;
import static ps.e7gz.booking.internal.OnlineCreationCondition.VENUE_ACCOUNT_ENABLED;
import static ps.e7gz.booking.internal.OnlineCreationCondition.VENUE_CURRENTLY_OPEN;
import static ps.e7gz.booking.internal.OnlineCreationCondition.VENUE_MARKETPLACE_ACTIVE;
import static ps.e7gz.booking.internal.OnlineCreationCondition.WITHIN_ONE_SESSION;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.identity.api.CustomerActivation;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.trust.api.ActiveBans;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.SessionSnapshots;
import ps.e7gz.venue.api.SessionSnapshots.Session;
import ps.e7gz.venue.api.VenueEligibility;
import ps.e7gz.venue.api.VenueEligibility.Eligibility;

/**
 * Decides the twenty {@link OnlineCreationCondition}s of a new Online Booking inside the creation command, after
 * {@code begin_command} has locked the Customer (and their commitment guard, Bookings and Bans), the Venue, its account
 * and the Resource - so no answer can change before the command commits (arch 05 §4.2). Every read goes through the
 * owner module's api on the command's transaction; time is only {@link CommandContext#commandNow()}.
 *
 * <p>The order of evaluation is not a product rule (PO decision 9 Oct 2026): the period shape (12-15) is checked before
 * the reads that need a valid period, and conditions 10, 11, 16, 17 and 18 are one call of the canonical availability
 * predicate ({@link AvailabilityQuery#isFree}, arch 01 §15 rule 1), never re-implemented here. The database stays the
 * authority at write time: its guards and exclusion constraints refuse what changed or raced (arch 01 §15 rule 2).
 */
@Component
class OnlineCreationPredicate {

    /** FR-BOK-03: at most 4 active/upcoming Online commitments. */
    static final int MAX_COMMITMENTS = 4;
    /** FR-BOK-09, §21: intake stops 10 minutes before the current Operating Session ends. */
    static final Duration INTAKE_CUTOFF = Duration.ofMinutes(10);
    /** FR-BOK-02: {@code now <= startAt - 15 minutes}; exactly 15 minutes is allowed. */
    static final Duration MINIMUM_LEAD_TIME = Duration.ofMinutes(15);
    /** FR-BOK-01: 1 to 6 hours in 30-minute steps. */
    static final int MIN_MINUTES = 60;
    static final int MAX_MINUTES = 360;
    static final int STEP_MINUTES = 30;

    private final CustomerActivation activation;
    private final ActiveBans bans;
    private final OnlineBookingStore store;
    private final VenueEligibility venues;
    private final AreaLookup areas;
    private final OperatingSessions sessions;
    private final SessionSnapshots snapshots;
    private final AvailabilityQuery availability;
    private final ResourceCatalog resources;
    private final BookingPricing pricing;

    OnlineCreationPredicate(CustomerActivation activation, ActiveBans bans, OnlineBookingStore store,
                            VenueEligibility venues, AreaLookup areas, OperatingSessions sessions,
                            SessionSnapshots snapshots, AvailabilityQuery availability, ResourceCatalog resources,
                            BookingPricing pricing) {
        this.activation = activation;
        this.bans = bans;
        this.store = store;
        this.venues = venues;
        this.areas = areas;
        this.sessions = sessions;
        this.snapshots = snapshots;
        this.availability = availability;
        this.resources = resources;
        this.pricing = pricing;
    }

    /**
     * All twenty conditions pass, or the first failure found is thrown.
     *
     * @param resource the Resource the request names, found before the command (an unknown one is NOT_FOUND there)
     * @throws CreationRefused naming the failed condition and its public code
     */
    Approval check(CommandContext command, OnlineBookingRequest request, ResourceCatalog.Entry resource) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(resource, "resource");
        Instant now = command.commandNow();
        UUID customer = request.customerId();
        UUID venueId = resource.venueId();

        // 1 - FR-ACC-03 (Spec §22 #1); an anonymised account answers ACCOUNT_DELETED (FR-ACC-10)
        try {
            activation.requirePhoneVerified(command, customer);
        } catch (ApiException refused) {
            throw new CreationRefused(PHONE_VERIFIED, refused.code());
        }
        // 2 - FR-ACC-17 (Spec §22 #2)
        if (bans.bannedAt(command, customer, now)) {
            throw new CreationRefused(NOT_BANNED);
        }
        // 3 - FR-BOK-03 (Spec §22 #3); the commitment guard row is locked by begin_command
        if (store.countCommitments(command, customer) >= MAX_COMMITMENTS) {
            throw new CreationRefused(BELOW_COMMITMENT_LIMIT);
        }

        // 5-7 - category A (Spec §22 #5-#7); the customer is never told which (PO decision 9 Oct 2026)
        Eligibility venue = venues.find(command, venueId)
                .orElseThrow(() -> new IllegalStateException("The Resource's Venue " + venueId + " does not exist"));
        if (venue.marketplaceStatus() != MarketplaceStatus.ACTIVE) {
            throw new CreationRefused(VENUE_MARKETPLACE_ACTIVE);
        }
        if (venue.accountStatus() != VenueAccountStatus.ENABLED) {
            throw new CreationRefused(VENUE_ACCOUNT_ENABLED);
        }
        if (!areas.isActive(command, venue.areaId())) {
            throw new CreationRefused(AREA_ACTIVE);
        }

        // 8-9 - the Online Intake Window (§21, Spec §22 #8-#9): open now, and now < end - 10 min
        OperatingSessions.SessionPeriod current = sessions.endingAfter(command, venueId, now).stream()
                .filter(session -> !session.start().isAfter(now))
                .findFirst()
                .orElseThrow(() -> new CreationRefused(VENUE_CURRENTLY_OPEN));
        if (!now.isBefore(current.end().minus(INTAKE_CUTOFF))) {
            throw new CreationRefused(BEFORE_INTAKE_CUTOFF);
        }

        // 12-15 - the period shape, before any read that needs a valid period (PO decision 9 Oct 2026)
        Instant startAt = request.startAt();
        if (now.isAfter(startAt.minus(MINIMUM_LEAD_TIME))) {
            throw new CreationRefused(LEAD_TIME);                                   // Spec §22 #11
        }
        durationFailure(request.durationMinutes()).ifPresent(failed -> {            // Spec §22 #12-#13
            throw new CreationRefused(failed);
        });
        // FR-BOK-07: endAt is real elapsed time; the price uses the nominal minutes
        Instant endAt = startAt.plus(Duration.ofMinutes(request.durationMinutes()));
        Session session = snapshots.containing(command, venueId, startAt)
                .filter(found -> !found.end().isBefore(endAt))
                .orElseThrow(() -> new CreationRefused(WITHIN_ONE_SESSION));      // FR-BOK-05, Spec §22 #14

        // 4 - FR-BOK-04 (Spec §22 #4); ex_customer_no_overlap stays the authority
        if (store.overlapsCommitment(command, customer, startAt, endAt)) {
            throw new CreationRefused(NO_OVERLAPPING_COMMITMENT);
        }

        // 10, 11, 16, 17, 18 - one call of the canonical predicate (arch 01 §15 rule 1)
        PredicateResult free = availability.isFree(command, request.resourceId(), new AvailabilityPeriod(startAt, endAt),
                now);
        Optional<AvailabilityCheck> failing = free.failingCheck();
        if (failing.isPresent()) {
            throw new CreationRefused(conditionOf(failing.get()));
        }

        // 19 - FR-VEN-42 (Spec §22 #19): a play mode exactly for PlayStation
        if ((resource.activity() == Activity.PLAYSTATION) != (request.playMode() != null)) {
            throw new CreationRefused(ACTIVITY_COMPATIBLE);
        }
        // 20 - all other product validation, including a price for the activity (PO decision 5 Oct 2026)
        BookingAmounts amounts = pricing.price(command, request.resourceId(), request.playMode(),
                        request.durationMinutes())
                .orElseThrow(() -> new CreationRefused(OTHER_PRODUCT_VALIDATION));

        String resourceName = resources.name(command, request.resourceId())
                .orElseThrow(() -> new IllegalStateException("Resource " + request.resourceId() + " vanished"));
        return new Approval(endAt, session, amounts, resourceName);
    }

    /** Conditions 13-14 (FR-BOK-01); the quote answers the same way (E7-39). Empty when the duration is valid. */
    static Optional<OnlineCreationCondition> durationFailure(int minutes) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            return Optional.of(DURATION_RANGE);
        }
        if (minutes % STEP_MINUTES != 0) {
            return Optional.of(DURATION_INCREMENT);
        }
        return Optional.empty();
    }

    /**
     * Availability checks 1-8 (arch 05 §2.1) as creation conditions: checks 5-8 - a declared emergency interval,
     * Maintenance, a Closure, an active commitment - are all condition 18 (Product v5.0 §16.3, PO decision 9 Oct 2026).
     */
    static OnlineCreationCondition conditionOf(AvailabilityCheck check) {
        return switch (check) {
            case VENUE_SELLING_BLOCK -> NO_VENUE_SELLING_BLOCK;
            case RESOURCE_SELLING_BLOCK -> NO_RESOURCE_SELLING_BLOCK;
            case RESOURCE_BASE_STATE -> RESOURCE_ACTIVE;
            case RESOURCE_OWNER_REVIEW -> RESOURCE_NOT_UNDER_OWNER_REVIEW;
            case EMERGENCY_INTERVAL, MAINTENANCE, CLOSURE, BLOCKING_RESERVATION -> NO_BLOCKING_RESERVATION;
        };
    }

    /**
     * What creation stores once every condition passed: the real-time end, the Operating Session the interval fits in,
     * the amounts and the Resource name snapshot.
     */
    record Approval(Instant endAt, Session session, BookingAmounts amounts, String resourceName) {

        Approval {
            Objects.requireNonNull(endAt, "endAt");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(amounts, "amounts");
            Objects.requireNonNull(resourceName, "resourceName");
        }
    }
}
