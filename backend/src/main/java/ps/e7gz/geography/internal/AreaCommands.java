package ps.e7gz.geography.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.geo.Coordinates;

/**
 * The Platform Admin's area commands (FR-VEN-12, FR-VEN-47; arch 09 section 5), each on the admin connection through
 * {@code admin.begin_command}. Whether an area may be deactivated is decided by the database
 * ({@code geo.guard_area_deactivation}, mapped to {@code AREA_HAS_ACTIVE_VENUES}); this code does not re-check it.
 */
final class AreaCommands {

    private AreaCommands() {
    }

    /** Admin commands lock no User or Venue for an area change; the area row itself is locked by the UPDATE. */
    private static CommandScope scope(UUID adminId, UUID correlationId) {
        return CommandScope.admin(adminId, correlationId, LockSet.empty());
    }

    static Area read(CommandContext context, UUID areaId) {
        return context.jdbc().sql("SELECT " + Area.COLUMNS + " FROM geo.area WHERE id = :id")
                .param("id", areaId).query(Area::map).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    /** A new area is active (column default; FR-VEN-12). */
    record Create(UUID adminId, UUID correlationId, String name, String nameAr, Coordinates centroid)
            implements Command<Area> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;           // no Venue in scope (ADR-022)
        }

        @Override
        public CommandScope scope() {
            return AreaCommands.scope(adminId, correlationId);
        }

        @Override
        public Area execute(CommandContext context) {
            UUID id = UUID.randomUUID();
            context.jdbc().sql("INSERT INTO geo.area (id, name, name_ar, centroid) VALUES (:id, :name, :nameAr, "
                            + (centroid == null ? "NULL" : Coordinates.SQL_POINT) + ")")
                    .param("id", id).param("name", name).param("nameAr", nameAr)
                    .param("lat", centroid == null ? null : centroid.lat())
                    .param("lng", centroid == null ? null : centroid.lng()).update();
            return read(context, id);
        }
    }

    /** Renames or moves the centroid; a null field is left unchanged, the remove flags clear the optional values. */
    record Update(UUID adminId, UUID correlationId, UUID areaId, String name, String nameAr, boolean removeNameAr,
                  Coordinates centroid, boolean removeCentroid) implements Command<Area> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return AreaCommands.scope(adminId, correlationId);
        }

        @Override
        public Area execute(CommandContext context) {
            int updated = context.jdbc().sql("""
                            UPDATE geo.area
                               SET name = COALESCE(:name, name),
                                   name_ar = %s,
                                   centroid = %s
                             WHERE id = :id""".formatted(removeNameAr ? "NULL" : "COALESCE(:nameAr, name_ar)",
                            removeCentroid ? "NULL" : centroid == null ? "centroid" : Coordinates.SQL_POINT))
                    .param("id", areaId).param("name", name).param("nameAr", nameAr)
                    .param("lat", centroid == null ? null : centroid.lat())
                    .param("lng", centroid == null ? null : centroid.lng()).update();
            if (updated == 0) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            return read(context, areaId);
        }
    }

    /**
     * Activates or deactivates an area. Setting the status it already has changes nothing and writes no audit row.
     */
    record SetActive(UUID adminId, UUID correlationId, UUID areaId, boolean active, AuditWriter audit)
            implements Command<Area> {

        SetActive {
            Objects.requireNonNull(audit, "audit");
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
        }

        @Override
        public CommandScope scope() {
            return AreaCommands.scope(adminId, correlationId);
        }

        @Override
        public Area execute(CommandContext context) {
            Optional<Boolean> current = context.jdbc().sql("SELECT is_active FROM geo.area WHERE id = :id FOR UPDATE")
                    .param("id", areaId).query(Boolean.class).optional();
            if (current.isEmpty()) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            if (current.get() != active) {
                // geo.guard_area_deactivation refuses while a Venue in the area is ACTIVE or SUSPENDED (FR-VEN-47).
                context.jdbc().sql("UPDATE geo.area SET is_active = :active WHERE id = :id")
                        .param("active", active).param("id", areaId).update();
                audit.write(context, AuditRecord.builder(
                        active ? GeographyAuditAction.AREA_ACTIVATED : GeographyAuditAction.AREA_DEACTIVATED,
                        AuditActor.admin(adminId), areaId, correlationId).build());
            }
            return read(context, areaId);
        }
    }
}
