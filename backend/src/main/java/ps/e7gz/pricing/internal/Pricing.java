package ps.e7gz.pricing.internal;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.money.Money;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;
import ps.e7gz.venue.api.VenueActivities;

/**
 * Venue Dashboard pricing (FR-VEN-43…FR-VEN-45, arch 09 §4.2): the venue default prices - PlayStation per console ×
 * play mode, Billiards and Ping Pong one hourly rate - and PlayStation room overrides, which replace the default for
 * that room and are cleared by a console change (FR-VEN-15, {@code tr_clear_resource_price_overrides_on_console_change}).
 * Every write needs the Owner Password (FRS §2.4).
 */
@Service
public class Pricing {

    /** {@code numeric(12,2)}: the largest price the columns hold. */
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    private final VenueAccountVenues accounts;
    private final VenueActivities activities;
    private final ResourceCatalog resources;
    private final OwnerPasswordGate gate;
    private final AuditWriter audit;
    private final JdbcClient jdbc;

    public Pricing(OwnerPasswordGate gate, VenueAccountVenues accounts, VenueActivities activities,
                   ResourceCatalog resources, AuditWriter audit, DataSource appDataSource) {
        this.accounts = accounts;
        this.activities = activities;
        this.resources = resources;
        this.gate = gate;
        this.audit = audit;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** One venue default: console and play mode exactly for PlayStation ({@code ck_price_dimensions}). */
    public record DefaultPrice(Activity activity, ConsoleType consoleType, PlayMode playMode, Money hourlyPrice) {
    }

    /** One room override, always for the room's current console ({@code tr_validate_resource_price_override}). */
    public record RoomOverride(UUID resourceId, ConsoleType consoleType, PlayMode playMode, Money hourlyPrice) {
    }

    /** The venue's prices: defaults and the overrides of its rooms. */
    public record View(List<DefaultPrice> defaults, List<RoomOverride> overrides) {
    }

    /** A price as sent: console and play mode exactly for PlayStation, checked here with a field error. */
    public record DefaultInput(Activity activity, ConsoleType consoleType, PlayMode playMode,
                               Money hourlyPrice) {
    }

    public record OverrideInput(PlayMode playMode, Money hourlyPrice) {
    }

    public View get(UUID venueAccountId) {
        return view(accounts.venueOf(venueAccountId));
    }

    public View setDefaults(UUID venueAccountId, List<DefaultInput> input, String ownerPassword) {
        UUID venueId = accounts.venueOf(venueAccountId);
        List<DefaultPrice> defaults = validateDefaults(input);
        UUID correlationId = CorrelationId.require();
        gate.run(new PricingCommands.SetDefaults(venueAccountId, venueId, correlationId, defaults,
                ownerPassword, gate, activities, audit), venueAccountId, correlationId);
        return view(venueId);
    }

    public View setOverrides(UUID venueAccountId, UUID resourceId, List<OverrideInput> input, String ownerPassword) {
        List<RoomOverride> prices = validateOverrides(resourceId, input);
        if (prices.isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "prices");
        }
        return writeOverrides(venueAccountId, resourceId, prices, ownerPassword);
    }

    public View resetOverrides(UUID venueAccountId, UUID resourceId, String ownerPassword) {
        return writeOverrides(venueAccountId, resourceId, List.of(), ownerPassword);
    }

    private View writeOverrides(UUID venueAccountId, UUID resourceId, List<RoomOverride> prices,
                                String ownerPassword) {
        UUID venueId = accounts.venueOf(venueAccountId);
        // Checked before the command: a lock set naming a Resource that does not exist is refused by begin_command
        // itself. Resources are never deleted (archived only), so the answer cannot change before the command runs.
        if (resources.ofVenue(venueId).stream().noneMatch(entry -> entry.resourceId().equals(resourceId))) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
        UUID correlationId = CorrelationId.require();
        gate.run(new PricingCommands.SetOverrides(venueAccountId, venueId, resourceId, correlationId, prices,
                ownerPassword, gate, resources, audit), venueAccountId, correlationId);
        return view(venueId);
    }

    private View view(UUID venueId) {
        List<ResourceCatalog.Entry> rooms = resources.ofVenue(venueId);
        Map<UUID, Integer> order = new HashMap<>();
        for (int i = 0; i < rooms.size(); i++) {
            order.put(rooms.get(i).resourceId(), i);
        }
        List<RoomOverride> overrides = PricingStore.overrides(jdbc, order.keySet()).stream()
                .sorted(Comparator.<RoomOverride>comparingInt(o -> order.get(o.resourceId()))
                        .thenComparing(RoomOverride::playMode))
                .toList();
        return new View(PricingStore.defaults(jdbc, venueId), overrides);
    }

    /** ck_price_dimensions, ux_default_price and the column range, as field errors before any write. */
    private static List<DefaultPrice> validateDefaults(List<DefaultInput> input) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            DefaultInput row = Objects.requireNonNull(input.get(i), "row");
            String at = "defaults[" + i + "].";
            boolean playStation = row.activity() == Activity.PLAYSTATION;
            if (playStation != (row.consoleType() != null)) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, at + "consoleType");
            }
            if (playStation != (row.playMode() != null)) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, at + "playMode");
            }
            requirePrice(row.hourlyPrice(), at + "hourlyPrice");
            if (!seen.add(row.activity() + "/" + row.consoleType() + "/" + row.playMode())) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "defaults[" + i + "]");
            }
        }
        return input.stream()
                .map(row -> new DefaultPrice(row.activity(), row.consoleType(), row.playMode(), row.hourlyPrice()))
                .sorted(PricingStore.DEFAULT_ORDER).toList();
    }

    /** uq_resource_price_override and the column range; the console is the room's own, set in the command. */
    private static List<RoomOverride> validateOverrides(UUID resourceId, List<OverrideInput> input) {
        Set<PlayMode> seen = new HashSet<>();
        for (int i = 0; i < input.size(); i++) {
            OverrideInput row = Objects.requireNonNull(input.get(i), "row");
            requirePrice(row.hourlyPrice(), "prices[" + i + "].hourlyPrice");
            if (!seen.add(row.playMode())) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "prices[" + i + "].playMode");
            }
        }
        return input.stream().map(row -> new RoomOverride(resourceId, null, row.playMode(), row.hourlyPrice()))
                .sorted(Comparator.comparing(RoomOverride::playMode)).toList();
    }

    private static void requirePrice(Money price, String field) {
        if (price.isNegative() || price.amount().compareTo(MAX_PRICE) > 0) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, field);
        }
    }
}
