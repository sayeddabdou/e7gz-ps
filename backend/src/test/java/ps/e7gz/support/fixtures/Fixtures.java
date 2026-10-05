package ps.e7gz.support.fixtures;

import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;

/**
 * Shared test data builders for every lane (E7-28): {@code aVenue().active().create()},
 * {@code aResource().at(venue).playStation(ConsoleType.PS4).create()}, {@code aUser().verified().create()} and so on.
 * Each builder fills every column the database requires with a valid, unique value, so a test names only what it is
 * about. The rows are written straight to the tables (the database still checks every constraint and trigger), not
 * through the module services: use the services when the test is about them.
 *
 * <p>{@code create()} commits as the migration owner; {@code create(db)} writes on the given connection, e.g. a
 * command's {@code ctx.jdbc()} inside a rolled-back test command. A builder that needs a parent it was not given
 * (a venue without an area, a resource without a venue) creates one on the same connection.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static AreaBuilder anArea() {
        return new AreaBuilder();
    }

    public static VenueBuilder aVenue() {
        return new VenueBuilder();
    }

    public static VenueAccountBuilder aVenueAccount() {
        return new VenueAccountBuilder();
    }

    public static ResourceBuilder aResource() {
        return new ResourceBuilder();
    }

    public static UserBuilder aUser() {
        return new UserBuilder();
    }

    public static AdminBuilder anAdmin() {
        return new AdminBuilder();
    }

    public static DefaultPriceBuilder aDefaultPrice() {
        return new DefaultPriceBuilder();
    }

    public static PriceOverrideBuilder aPriceOverride() {
        return new PriceOverrideBuilder();
    }

    public static OpeningTemplateBuilder anOpeningTemplate() {
        return new OpeningTemplateBuilder();
    }

    public static OperatingSessionBuilder anOperatingSession() {
        return new OperatingSessionBuilder();
    }

    // ------------------------------------------------------------------------------------------ created rows

    public record Area(UUID id, String name) {
    }

    public record Venue(UUID id, UUID areaId, String name) {
    }

    public record VenueAccount(UUID id, UUID venueId, String loginIdentifier) {
    }

    public record Resource(UUID id, UUID venueId, Activity activity, String name, ConsoleType consoleType) {
    }

    /** A Customer; {@code localPhone} is the form a person types at login ({@code 010…}). */
    public record User(UUID id, String username, String localPhone, String phoneE164) {
    }

    public record Admin(UUID id, String email) {
    }

    /** An operating session and the opening template it was generated from. */
    public record OperatingSession(UUID id, UUID venueId, UUID templateId) {
    }
}
