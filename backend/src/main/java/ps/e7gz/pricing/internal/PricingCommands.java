package ps.e7gz.pricing.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.pricing.internal.Pricing.DefaultPrice;
import ps.e7gz.pricing.internal.Pricing.RoomOverride;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueActivities;

/**
 * Pricing writes. Each opens the declared command transaction before its DML (arch 04 §2.1), checks the Owner Password
 * first (FRS §2.4, FR-VEN-03) and writes an audit row with the previous and new prices.
 */
final class PricingCommands {

    private PricingCommands() {
    }

    /** Sets the venue default prices sent (FR-VEN-43, FR-VEN-44); a price not sent keeps its value. */
    @OwnerProtected("VENUE_PRICING_CHANGE")
    record SetDefaults(UUID venueAccountId, UUID venueId, UUID correlationId, List<DefaultPrice> defaults,
                           String ownerPassword, OwnerPasswordGate gate, VenueActivities activities,
                           AuditWriter audit) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Void execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verify(context, venueAccountId, ownerPassword);
            // fk_default_price_venue_activity refuses it too, but maps to VENUE_ACTIVITY_IN_USE (removing an
            // activity); a price for an activity the venue does not offer is a bad input here.
            Set<Activity> offered = activities.offered(context, venueId);
            for (int i = 0; i < defaults.size(); i++) {
                if (!offered.contains(defaults.get(i).activity())) {
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "defaults[" + i + "].activity");
                }
            }
            List<DefaultPrice> previous = PricingStore.defaults(context.jdbc(), venueId);
            PricingStore.upsertDefaults(context.jdbc(), venueId, defaults);
            audit.write(context, AuditRecord.builder(PricingAuditAction.VENUE_PRICING_CHANGED,
                            AuditActor.venue(venueAccountId), venueId, correlationId)
                    .ownerPasswordCheck(check).contextVenue(venueId)
                    .payload(Map.of("previous", previous.stream().map(PricingCommands::row).toList(),
                            "defaults", defaults.stream().map(PricingCommands::row).toList()))
                    .build());
            return null;
        }

        @Override
        public String toString() {
            return "SetDefaults[venueId=" + venueId + ", rows=" + defaults.size() + ", <owner password redacted>]";
        }
    }

    /**
     * Sets a PlayStation room's overrides for its current console (FR-VEN-43), replacing the ones it had; an empty set
     * is a reset to the venue defaults. Locks the room, so a console change (which clears overrides, FR-VEN-15) cannot
     * interleave.
     */
    @OwnerProtected("ROOM_PRICE_OVERRIDE_SET_RESET")
    record SetOverrides(UUID venueAccountId, UUID venueId, UUID resourceId, UUID correlationId,
                        List<RoomOverride> prices, String ownerPassword, OwnerPasswordGate gate,
                        ResourceCatalog resources, AuditWriter audit) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).resources(resourceId).build());
        }

        @Override
        public Void execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verify(context, venueAccountId, ownerPassword);
            ResourceCatalog.Entry room = resources.find(context, resourceId)
                    .filter(entry -> entry.venueId().equals(venueId))
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            boolean reset = prices.isEmpty();
            if (!reset && room.activity() != Activity.PLAYSTATION) {
                // FR-VEN-44: no table-level override for Billiards and Ping Pong.
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "resourceId");
            }
            List<RoomOverride> previous = PricingStore.overrides(context.jdbc(), List.of(resourceId));
            PricingStore.replaceOverrides(context.jdbc(), resourceId, room.consoleType(), prices);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("venueId", venueId);
            if (room.consoleType() != null) {
                payload.put("consoleType", room.consoleType());
            }
            payload.put("previous", previous.stream().map(PricingCommands::row).toList());
            if (!reset) {
                payload.put("prices", prices.stream().map(PricingCommands::row).toList());
            }
            audit.write(context, AuditRecord.builder(reset ? PricingAuditAction.ROOM_PRICE_OVERRIDE_RESET
                                    : PricingAuditAction.ROOM_PRICE_OVERRIDE_SET, AuditActor.venue(venueAccountId),
                            resourceId, correlationId)
                    .ownerPasswordCheck(check).contextVenue(venueId).payload(payload).build());
            return null;
        }

        @Override
        public String toString() {
            return "SetOverrides[resourceId=" + resourceId + ", rows=" + prices.size() + ", <owner password redacted>]";
        }
    }

    private static Map<String, Object> row(DefaultPrice price) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("activity", price.activity());
        if (price.consoleType() != null) {
            row.put("consoleType", price.consoleType());
            row.put("playMode", price.playMode());
        }
        row.put("hourlyPrice", price.hourlyPrice().amount());
        return row;
    }

    private static Map<String, Object> row(RoomOverride price) {
        return Map.of("playMode", price.playMode(), "hourlyPrice", price.hourlyPrice().amount());
    }
}
