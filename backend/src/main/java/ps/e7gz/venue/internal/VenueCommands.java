package ps.e7gz.venue.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.geo.Coordinates;

/**
 * The Platform Admin's venue commands (FR-ADM-02, FR-VEN-07, FR-VEN-12; arch 09 section 5), each on the admin
 * connection through {@code admin.begin_command}. Area rules are the database's ({@code venue.guard_venue_area}):
 * a venue may only be placed in an active area, and may not be ACTIVE or SUSPENDED in an inactive one
 * ({@code AREA_INACTIVE}).
 */
final class VenueCommands {

    /** All venues are in one city (Product Specification v5.0 section 1); arch 04 section 9.3. */
    static final String TIMEZONE = "Africa/Cairo";

    private VenueCommands() {
    }

    static Venue read(CommandContext context, UUID venueId) {
        return context.jdbc().sql(Venue.SELECT + " WHERE v.id = :id").param("id", venueId).query(Venue::map)
                .optional().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    /**
     * Makes the venue's activities exactly {@code activities}: missing ones are added and the others removed. Removing
     * one that rooms, prices or availability still use is refused by the database ({@code VENUE_ACTIVITY_IN_USE}).
     */
    static void replaceActivities(CommandContext context, UUID venueId, Set<Activity> activities) {
        List<String> names = activities.stream().map(Activity::name).toList();
        context.jdbc().sql("DELETE FROM venue.venue_activity WHERE venue_id = :venue AND NOT (activity = ANY(:names))")
                .param("venue", venueId).param("names", names.toArray(String[]::new)).update();
        for (String name : names) {
            context.jdbc().sql("""
                            INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (:id, :venue, :activity)
                            ON CONFLICT (venue_id, activity) DO NOTHING""")
                    .param("id", UUID.randomUUID()).param("venue", venueId).param("activity", name).update();
        }
    }

    /** A new venue starts in ONBOARDING (FR-ADM-02). */
    record Create(UUID adminId, UUID correlationId, UUID areaId, String name, String address, Coordinates location,
                  String phone, Set<Activity> activities) implements Command<Venue> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;          // onboarding, not marketplace (ADR-022)
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.empty());   // the venue does not exist yet
        }

        @Override
        public Venue execute(CommandContext context) {
            UUID id = UUID.randomUUID();
            context.jdbc().sql("""
                            INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status,
                                                     timezone, phone)
                            VALUES (:id, :area, :name, :address, %s, 'ONBOARDING', :timezone, :phone)"""
                            .formatted(Coordinates.SQL_POINT))
                    .param("id", id).param("area", areaId).param("name", name).param("address", address)
                    .param("lat", location.lat()).param("lng", location.lng()).param("timezone", TIMEZONE)
                    .param("phone", phone).update();
            replaceActivities(context, id, activities);
            return read(context, id);
        }
    }

    /** Edits venue information; a null field (or null {@code activities}) is left unchanged. */
    record Update(UUID adminId, UUID correlationId, UUID venueId, UUID areaId, String name, String address,
                  Coordinates location, String phone, Set<Activity> activities) implements Command<Venue> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().venues(venueId).build());
        }

        @Override
        public Venue execute(CommandContext context) {
            int updated = context.jdbc().sql("""
                            UPDATE venue.venue
                               SET area_id = COALESCE(:area, area_id),
                                   name = COALESCE(:name, name),
                                   address = COALESCE(:address, address),
                                   phone = COALESCE(:phone, phone),
                                   location = %s
                             WHERE id = :id""".formatted(location == null ? "location" : Coordinates.SQL_POINT))
                    .param("id", venueId).param("area", areaId).param("name", name).param("address", address)
                    .param("phone", phone).param("lat", location == null ? null : location.lat())
                    .param("lng", location == null ? null : location.lng()).update();
            if (updated == 0) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            if (activities != null) {
                replaceActivities(context, venueId, activities);
            }
            return read(context, venueId);
        }
    }

    /**
     * One marketplace status action with the admin's reason (arch 03 section 5 {@code setMarketplaceStatus(…, reason)}).
     * The action decides here whether the venue's current status allows it ({@code MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED},
     * E7-22 contract); the area rule stays with the database.
     */
    record ChangeStatus(UUID adminId, UUID correlationId, UUID venueId, MarketplaceAction action, String reason,
                        AuditWriter audit) implements Command<Venue> {

        ChangeStatus {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(audit, "audit");
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().venues(venueId).build());
        }

        @Override
        public Venue execute(CommandContext context) {
            MarketplaceStatus current = context.jdbc()
                    .sql("SELECT marketplace_status FROM venue.venue WHERE id = :id FOR UPDATE")
                    .param("id", venueId).query(String.class).optional().map(MarketplaceStatus::valueOf)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (!action.allowedFrom(current)) {
                throw new ApiException(ApiErrorCode.MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED);
            }
            MarketplaceStatus target = action.target();
            context.jdbc().sql("UPDATE venue.venue SET marketplace_status = :status WHERE id = :id")
                    .param("status", target.name()).param("id", venueId).update();
            audit.write(context, AuditRecord.builder(VenueAuditAction.VENUE_MARKETPLACE_STATUS_CHANGED,
                            AuditActor.admin(adminId), venueId, correlationId)
                    .reason(reason)
                    .payload(Map.of("previousStatus", current.name(), "newStatus", target.name()))
                    .build());
            return read(context, venueId);
        }
    }
}
