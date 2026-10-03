package ps.e7gz.venue.internal;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerPasswordNotRequired;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.venue.api.VenuePhotos.Actor;

/**
 * Venue photo writes (PCR-005), each locking the venue so the visible-photo limit and the order cannot race. The
 * Platform Admin runs on the admin connection ({@code admin.begin_command}); the venue on the app connection, with the
 * Owner Password checked first inside the transaction. Object storage is never called in here (arch 01 §15).
 */
final class VenuePhotoCommands {

    private VenuePhotoCommands() {
    }

    static CommandScope scope(Actor actor, UUID venueId) {
        LockSet venue = LockSet.builder().venues(venueId).build();
        return switch (actor) {
            case Actor.Admin admin -> CommandScope.admin(admin.adminId(), admin.correlationId(), venue);
            case Actor.Venue ignored -> CommandScope.shared(venue);
        };
    }

    static AuditActor auditActor(Actor actor) {
        return switch (actor) {
            case Actor.Admin admin -> AuditActor.admin(admin.adminId());
            case Actor.Venue venue -> AuditActor.venue(venue.venueAccountId());
        };
    }

    static UUID correlationId(Actor actor) {
        return switch (actor) {
            case Actor.Admin admin -> admin.correlationId();
            case Actor.Venue venue -> venue.correlationId();
        };
    }

    /** FRS §2.4 as amended by PCR-005: the venue's add, reorder and remove need the Owner Password. */
    static OwnerPasswordCheck ownerPassword(Actor actor, CommandContext context) {
        return actor instanceof Actor.Venue venue ? venue.ownerPassword().verify(context) : null;
    }

    static int visibleCount(CommandContext context, UUID venueId) {
        return context.jdbc().sql("""
                        SELECT count(*) FROM venue.venue_image
                         WHERE venue_id = :venue AND uploaded_at IS NOT NULL AND removed_at IS NULL""")
                .param("venue", venueId).query(Integer.class).single();
    }

    static VenuePhotoRow lockedRow(CommandContext context, UUID venueId, UUID imageId) {
        return context.jdbc().sql(VenuePhotoRow.SELECT + " WHERE i.id = :id AND i.venue_id = :venue FOR UPDATE")
                .param("id", imageId).param("venue", venueId).query(VenuePhotoRow::map).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    static AuditRecord.Builder audit(VenuePhotoAuditAction action, Actor actor, UUID subjectId, OwnerPasswordCheck check) {
        AuditRecord.Builder record = AuditRecord.builder(action, auditActor(actor), subjectId, correlationId(actor));
        return check == null ? record : record.ownerPasswordCheck(check);
    }

    private abstract static class OnVenue<R> implements Command<R> {

        final UUID venueId;
        final Actor actor;

        OnVenue(UUID venueId, Actor actor) {
            this.venueId = venueId;
            this.actor = actor;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;      // venue content, not marketplace eligibility
        }

        @Override
        public CommandScope scope() {
            return VenuePhotoCommands.scope(actor, venueId);
        }
    }

    /**
     * Records the upload; the photo stays hidden until confirmed. Refused when the venue is already at its limit. Only
     * returns a signed PUT link, so no Owner Password (PCR-005; arch 09 §4.2).
     */
    @OwnerPasswordNotRequired("VENUE_PHOTO_UPLOAD_START")
    static final class Start extends OnVenue<VenuePhotoRow> {

        private final String contentType;
        private final int maxVisible;

        Start(UUID venueId, Actor actor, String contentType, int maxVisible) {
            super(venueId, actor);
            this.contentType = contentType;
            this.maxVisible = maxVisible;
        }

        @Override
        public VenuePhotoRow execute(CommandContext context) {
            if (visibleCount(context, venueId) >= maxVisible) {
                throw new ApiException(ApiErrorCode.VENUE_IMAGE_LIMIT_REACHED);
            }
            UUID id = UUID.randomUUID();
            context.jdbc().sql("""
                            INSERT INTO venue.venue_image (id, venue_id, object_key, content_type, created_at)
                            VALUES (:id, :venue, :key, :type, :createdAt)""")
                    .param("id", id).param("venue", venueId)
                    .param("key", VenuePhotoRow.originalKey(venueId, id, contentType)).param("type", contentType)
                    .param("createdAt", VenuePhotoRow.utc(context.commandNow())).update();
            return lockedRow(context, venueId, id);
        }
    }

    /**
     * The checked upload becomes visible, last in the order (PCR-005). A visible photo is returned unchanged. The venue
     * needs the Owner Password (PCR-005 amends FRS §2.4); the Platform Admin does not.
     */
    @OwnerProtected("VENUE_PHOTO_ADD")
    static final class Confirm extends OnVenue<VenuePhotoRow> {

        private final UUID imageId;
        private final int maxVisible;
        private final AuditWriter audit;

        Confirm(UUID venueId, UUID imageId, Actor actor, int maxVisible, AuditWriter audit) {
            super(venueId, actor);
            this.imageId = imageId;
            this.maxVisible = maxVisible;
            this.audit = audit;
        }

        @Override
        public VenuePhotoRow execute(CommandContext context) {
            OwnerPasswordCheck check = ownerPassword(actor, context);
            VenuePhotoRow row = lockedRow(context, venueId, imageId);
            if (row.removed()) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            if (row.uploaded()) {
                return row;
            }
            if (visibleCount(context, venueId) >= maxVisible) {
                throw new ApiException(ApiErrorCode.VENUE_IMAGE_LIMIT_REACHED);
            }
            context.jdbc().sql("""
                            UPDATE venue.venue_image
                               SET uploaded_at = :now,
                                   sort_order = (SELECT coalesce(max(sort_order), 0) + 1 FROM venue.venue_image
                                                  WHERE venue_id = :venue AND uploaded_at IS NOT NULL
                                                    AND removed_at IS NULL)
                             WHERE id = :id""")
                    .param("now", VenuePhotoRow.utc(context.commandNow())).param("venue", venueId)
                    .param("id", imageId).update();
            audit.write(context, audit(VenuePhotoAuditAction.VENUE_PHOTO_ADDED, actor, imageId, check)
                    .payload(Map.of("venueId", venueId, "contentType", row.contentType())).build());
            return lockedRow(context, venueId, imageId);
        }
    }

    /** Positions 1…n in the given order; the list must name every visible photo exactly once. Venue: Owner Password. */
    @OwnerProtected("VENUE_PHOTO_REORDER")
    static final class Reorder extends OnVenue<Void> {

        private final List<UUID> imageIds;
        private final AuditWriter audit;

        Reorder(UUID venueId, List<UUID> imageIds, Actor actor, AuditWriter audit) {
            super(venueId, actor);
            this.imageIds = List.copyOf(imageIds);
            this.audit = audit;
        }

        @Override
        public Void execute(CommandContext context) {
            OwnerPasswordCheck check = ownerPassword(actor, context);
            List<UUID> visible = context.jdbc().sql(VenuePhotoRow.VISIBLE + " FOR UPDATE").param("venue", venueId)
                    .query(VenuePhotoRow::map).list().stream().map(VenuePhotoRow::id).toList();
            if (imageIds.size() != visible.size() || !new HashSet<>(imageIds).equals(new HashSet<>(visible))) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "imageIds");
            }
            for (int i = 0; i < imageIds.size(); i++) {
                context.jdbc().sql("UPDATE venue.venue_image SET sort_order = :position WHERE id = :id")
                        .param("position", i + 1).param("id", imageIds.get(i)).update();
            }
            audit.write(context, audit(VenuePhotoAuditAction.VENUE_PHOTOS_REORDERED, actor, venueId, check)
                    .payload(Map.of("imageIds", imageIds)).build());
            return null;
        }
    }

    /** Hides a visible photo; the row and the stored original stay (PCR-005). Venue: Owner Password. */
    @OwnerProtected("VENUE_PHOTO_REMOVE")
    static final class Remove extends OnVenue<Void> {

        private final UUID imageId;
        private final AuditWriter audit;

        Remove(UUID venueId, UUID imageId, Actor actor, AuditWriter audit) {
            super(venueId, actor);
            this.imageId = imageId;
            this.audit = audit;
        }

        @Override
        public Void execute(CommandContext context) {
            OwnerPasswordCheck check = ownerPassword(actor, context);
            VenuePhotoRow row = lockedRow(context, venueId, imageId);
            if (!row.visible()) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            context.jdbc().sql("UPDATE venue.venue_image SET removed_at = :now WHERE id = :id")
                    .param("now", VenuePhotoRow.utc(context.commandNow())).param("id", imageId).update();
            audit.write(context, audit(VenuePhotoAuditAction.VENUE_PHOTO_REMOVED, actor, imageId, check)
                    .payload(Map.of("venueId", venueId)).build());
            return null;
        }
    }

    /** Worker: the copies of a confirmed photo exist. A photo removed meanwhile keeps processed_at unset. */
    record MarkProcessed(UUID venueId, UUID imageId) implements Command<Boolean> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Boolean execute(CommandContext context) {
            return context.jdbc().sql("""
                            UPDATE venue.venue_image SET processed_at = :now
                             WHERE id = :id AND uploaded_at IS NOT NULL AND processed_at IS NULL AND removed_at IS NULL""")
                    .param("now", VenuePhotoRow.utc(context.commandNow())).param("id", imageId).update() == 1;
        }
    }

    /**
     * Nightly: uploads not confirmed within {@code abandonAfter} are hidden (PO decision 3 Oct 2026; they were never
     * shown). Returns their storage keys so the caller deletes the files after commit.
     */
    record HideAbandoned(Duration abandonAfter) implements Command<List<String>> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());       // never-visible rows: no venue state depends on them
        }

        @Override
        public List<String> execute(CommandContext context) {
            return context.jdbc().sql("""
                            UPDATE venue.venue_image SET removed_at = :now
                             WHERE uploaded_at IS NULL AND removed_at IS NULL AND created_at < :cutoff
                            RETURNING object_key""")
                    .param("now", VenuePhotoRow.utc(context.commandNow()))
                    .param("cutoff", VenuePhotoRow.utc(context.commandNow().minus(abandonAfter)))
                    .query(String.class).list();
        }
    }
}
