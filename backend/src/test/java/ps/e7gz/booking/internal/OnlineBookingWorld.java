package ps.e7gz.booking.internal;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aDefaultPrice;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.support.fixtures.ResourceBuilder;

/**
 * One fresh bookable world per test, built around the database's own clock ({@code t0}): an ACTIVE Venue in an active
 * Area with its ENABLED account, a PS5 room priced 77.77 EGP an hour (PS5 single player), a verified Customer, and -
 * unless the test builds its own - one Operating Session {@code [t0-2h, t0+10h)} (Africa/Cairo wall clock). Every row
 * is new and uniquely named, so tests never share state.
 */
final class OnlineBookingWorld {

    static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    static final String HOURLY = "77.77";
    static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    final Instant t0;
    final Fixtures.Venue venue;
    final Fixtures.Resource room;
    final Fixtures.User customer;
    /** The venue's shared account (E7-47: the venue answers as it). */
    final Fixtures.VenueAccount account;

    private OnlineBookingWorld(Instant t0, Fixtures.Venue venue, Fixtures.Resource room, Fixtures.User customer,
                               Fixtures.VenueAccount account) {
        this.t0 = t0;
        this.venue = venue;
        this.room = room;
        this.customer = customer;
        this.account = account;
    }

    static OnlineBookingWorld create() {
        return builder().create();
    }

    static Builder builder() {
        return new Builder();
    }

    /** The database's clock, to the second: commands sample their instant from it ({@code begin_command}). */
    static Instant dbNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant()
                .truncatedTo(ChronoUnit.SECONDS);
    }

    /** A materialized session {@code [start, end)} labelled {@code day}, as the session generator would store it. */
    void session(Instant start, Instant end, int dayOffset) {
        LocalDateTime localStart = LocalDateTime.ofInstant(start, CAIRO);
        anOperatingSession().forVenue(venue)
                .on(localStart.toLocalDate().plusDays(dayOffset).toString(), localStart.toString(),
                        LocalDateTime.ofInstant(end, CAIRO).toString())
                .create();
    }

    /** Another room of the venue, priced like the first one when it is a PS5. */
    Fixtures.Resource anotherRoom(UnaryOperator<ResourceBuilder> how) {
        return how.apply(aResource().at(venue)).create();
    }

    UUID anotherCustomer() {
        return aUser().verified().create().id();
    }

    /** A whole minute about an hour after {@code t0}: inside the default session, well past the lead time. */
    Instant start() {
        return t0.plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MINUTES);
    }

    OnlineBookingRequest request(Instant startAt, int minutes) {
        return new OnlineBookingRequest(customer.id(), room.id(), PlayMode.SINGLE, startAt, minutes);
    }

    OnlineBookingRequest request(UUID customerId, Fixtures.Resource resource, Instant startAt, int minutes) {
        return new OnlineBookingRequest(customerId, resource.id(),
                resource.consoleType() == null ? null : PlayMode.SINGLE, startAt, minutes);
    }

    ResourceCatalog.Entry entry(Fixtures.Resource resource) {
        return new ResourceCatalog.Entry(resource.id(), resource.venueId(), resource.activity(), resource.consoleType());
    }

    static final class Builder {

        private MarketplaceStatus status = MarketplaceStatus.ACTIVE;
        private boolean accountDisabled;
        private boolean defaultSession = true;
        private boolean priced = true;
        private boolean verifiedCustomer = true;
        private UnaryOperator<ResourceBuilder> room = UnaryOperator.identity();

        Builder status(MarketplaceStatus status) {
            this.status = status;
            return this;
        }

        Builder accountDisabled() {
            this.accountDisabled = true;
            return this;
        }

        /** No session: the test adds its own with {@link #session}. */
        Builder withoutSession() {
            this.defaultSession = false;
            return this;
        }

        Builder unpriced() {
            this.priced = false;
            return this;
        }

        Builder unverifiedCustomer() {
            this.verifiedCustomer = false;
            return this;
        }

        Builder room(UnaryOperator<ResourceBuilder> room) {
            this.room = room;
            return this;
        }

        OnlineBookingWorld create() {
            Instant t0 = dbNow();
            Fixtures.Venue venue = aVenue().status(status).create();
            Fixtures.VenueAccount account = accountDisabled
                    ? aVenueAccount().forVenue(venue).disabled().create()
                    : aVenueAccount().forVenue(venue).create();
            Fixtures.Resource resource = room.apply(aResource().at(venue).playStation(ConsoleType.PS5)).create();
            if (priced) {
                aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.SINGLE).hourly(HOURLY).create();
            }
            Fixtures.User customer = verifiedCustomer ? aUser().verified().create() : aUser().unverified().create();
            OnlineBookingWorld world = new OnlineBookingWorld(t0, venue, resource, customer, account);
            if (defaultSession) {
                world.session(t0.minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES),
                        t0.plus(Duration.ofHours(10)).truncatedTo(ChronoUnit.MINUTES), 0);
            }
            return world;
        }
    }
}
