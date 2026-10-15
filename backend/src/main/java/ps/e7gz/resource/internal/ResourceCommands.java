package ps.e7gz.resource.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * Resource writes; every one opens the declared command transaction before business DML (arch 04 §2.1) and appends
 * its {@code resource.resource_state_change} row (append-only, {@code tr_resource_state_change_append_only}) and an
 * audit row (arch 10 §8.1). Venue actions check the Owner Password first (FRS §2.4, FR-VEN-03).
 */
final class ResourceCommands {

    private ResourceCommands() {
    }

    /** Who made a change, as {@code resource_state_change} and the audit trail record it. */
    record Actor(String type, UUID id, AuditActor audit, boolean ownerPasswordUsed) {

        static Actor admin(UUID adminId) {
            return new Actor("ADMIN", adminId, AuditActor.admin(adminId), false);
        }

        static Actor venue(UUID venueAccountId) {
            return new Actor("VENUE", venueAccountId, AuditActor.venue(venueAccountId), true);
        }
    }

    /** The Platform Admin adds a resource during onboarding (FR-ADM-02; PO decision 2 Oct 2026, E7-22). */
    record AdminCreate(UUID adminId, UUID correlationId, UUID venueId, Activity activity,
                       ConsoleType consoleType, AuditWriter audit) implements Command<Resources.Resource> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().venues(venueId).build());
        }

        @Override
        public Resources.Resource execute(CommandContext context) {
            return insert(context, venueId, activity, consoleType, Actor.admin(adminId), correlationId, null, audit);
        }
    }

    /** The venue adds a resource from the Dashboard (FRS §2.4 "protected resource configuration"; PO decision 2 Oct 2026). */
    @OwnerProtected("PROTECTED_RESOURCE_CONFIGURATION")
    record VenueCreate(UUID venueAccountId, UUID venueId, UUID correlationId, Activity activity,
                       ConsoleType consoleType, String ownerPassword, OwnerPasswordGate gate, AuditWriter audit)
            implements Command<Resources.Resource> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Resources.Resource execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verify(context, venueAccountId, ownerPassword);
            return insert(context, venueId, activity, consoleType, Actor.venue(venueAccountId), correlationId, check,
                    audit);
        }

        @Override
        public String toString() {
            return "VenueCreate[venueId=" + venueId + ", activity=" + activity + ", <owner password redacted>]";
        }
    }

    /**
     * Disable, re-enable, archive and restore (FR-VEN-03, Product Spec §13.4). Restore returns the resource to ACTIVE,
     * and an action that does not fit the current state is VALIDATION_FAILED on baseState (PO decisions 4 Oct 2026,
     * E7-26).
     */
    enum BaseStateAction {
        DISABLE(Set.of(ResourceBaseState.ACTIVE), ResourceBaseState.DISABLED, true,
                ResourceAuditAction.RESOURCE_DISABLED, IntegrationEvents.RESOURCE_DISABLED),
        RE_ENABLE(Set.of(ResourceBaseState.DISABLED), ResourceBaseState.ACTIVE, false,
                ResourceAuditAction.RESOURCE_RE_ENABLED, IntegrationEvents.RESOURCE_RE_ENABLED),
        ARCHIVE(Set.of(ResourceBaseState.ACTIVE, ResourceBaseState.DISABLED), ResourceBaseState.ARCHIVED, true,
                ResourceAuditAction.RESOURCE_ARCHIVED, IntegrationEvents.RESOURCE_ARCHIVED),
        RESTORE(Set.of(ResourceBaseState.ARCHIVED), ResourceBaseState.ACTIVE, false,
                ResourceAuditAction.RESOURCE_RESTORED, IntegrationEvents.RESOURCE_RESTORED);

        private final Set<ResourceBaseState> from;
        private final ResourceBaseState to;
        private final boolean blockedByReservations;
        private final ResourceAuditAction auditAction;
        /** Refreshes the venue's Nearest Availability rows at once (E7-56, PO decision 12 Oct 2026). */
        private final String event;

        BaseStateAction(Set<ResourceBaseState> from, ResourceBaseState to, boolean blockedByReservations,
                        ResourceAuditAction auditAction, String event) {
            this.from = from;
            this.to = to;
            this.blockedByReservations = blockedByReservations;
            this.auditAction = auditAction;
            this.event = event;
        }
    }

    @OwnerProtected("RESOURCE_DISABLE_REENABLE_ARCHIVE_RESTORE")
    record ChangeBaseState(UUID venueAccountId, UUID venueId, UUID resourceId, UUID correlationId,
                           BaseStateAction action, String ownerPassword, OwnerPasswordGate gate,
                           ActiveCommitments commitments, AuditWriter audit, Outbox outbox)
            implements Command<Resources.Resource> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).resources(resourceId).build());
        }

        @Override
        public Resources.Resource execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verify(context, venueAccountId, ownerPassword);
            Resources.Resource current = ofVenue(context, venueId, resourceId);
            if (!action.from.contains(current.baseState())) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "baseState");
            }
            if (action.blockedByReservations) {
                refuseWhenBlocked(context, commitments, resourceId);
            }
            context.jdbc().sql("UPDATE resource.resource SET base_state = :state WHERE id = :id")
                    .param("state", action.to.name()).param("id", resourceId).update();
            Actor actor = Actor.venue(venueAccountId);
            context.jdbc().sql("""
                            INSERT INTO resource.resource_state_change(
                                id, resource_id, from_base_state, to_base_state, owner_password_used, actor_type,
                                actor_id, correlation_id, changed_at)
                            VALUES (:id, :resource, :from, :to, :ownerPasswordUsed, :actorType, :actorId,
                                    :correlation, :changedAt)""")
                    .param("id", UUID.randomUUID()).param("resource", resourceId)
                    .param("from", current.baseState().name()).param("to", action.to.name())
                    .param("ownerPasswordUsed", actor.ownerPasswordUsed()).param("actorType", actor.type())
                    .param("actorId", actor.id()).param("correlation", correlationId)
                    .param("changedAt", utc(context)).update();
            audit.write(context, AuditRecord.builder(action.auditAction, actor.audit(), resourceId, correlationId)
                    .ownerPasswordCheck(check)
                    .stateChange(current.baseState().name(), action.to.name())
                    .contextVenue(venueId)
                    .payload(Map.of("venueId", venueId, "previousState", current.baseState().name(),
                            "newState", action.to.name()))
                    .build());
            outbox.publish(context, roomEvent(action.event, venueId, resourceId));
            return Resources.read(context.jdbc(), resourceId);
        }

        @Override
        public String toString() {
            return "ChangeBaseState[resourceId=" + resourceId + ", action=" + action + ", <owner password redacted>]";
        }
    }

    /**
     * PS4 ↔ PS5 (FR-VEN-14, FR-VEN-15): blocked like Disable; {@code tr_clear_resource_price_overrides_on_console_change}
     * clears the room's price overrides in the same transaction.
     */
    @OwnerProtected("PS_CONSOLE_CHANGE")
    record ChangeConsole(UUID venueAccountId, UUID venueId, UUID resourceId, UUID correlationId, ConsoleType target,
                         String ownerPassword, OwnerPasswordGate gate, ActiveCommitments commitments,
                         AuditWriter audit, Outbox outbox) implements Command<Resources.Resource> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).resources(resourceId).build());
        }

        @Override
        public Resources.Resource execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verify(context, venueAccountId, ownerPassword);
            Resources.Resource current = ofVenue(context, venueId, resourceId);
            if (current.activity() != Activity.PLAYSTATION || current.consoleType() == target) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "consoleType");
            }
            refuseWhenBlocked(context, commitments, resourceId);
            context.jdbc().sql("UPDATE resource.resource SET console_type = :console WHERE id = :id")
                    .param("console", target.name()).param("id", resourceId).update();
            Actor actor = Actor.venue(venueAccountId);
            context.jdbc().sql("""
                            INSERT INTO resource.resource_state_change(
                                id, resource_id, from_console, to_console, owner_password_used, actor_type, actor_id,
                                correlation_id, changed_at)
                            VALUES (:id, :resource, :from, :to, :ownerPasswordUsed, :actorType, :actorId,
                                    :correlation, :changedAt)""")
                    .param("id", UUID.randomUUID()).param("resource", resourceId)
                    .param("from", current.consoleType().name()).param("to", target.name())
                    .param("ownerPasswordUsed", actor.ownerPasswordUsed()).param("actorType", actor.type())
                    .param("actorId", actor.id()).param("correlation", correlationId)
                    .param("changedAt", utc(context)).update();
            audit.write(context, AuditRecord.builder(ResourceAuditAction.RESOURCE_CONSOLE_CHANGED, actor.audit(),
                            resourceId, correlationId)
                    .ownerPasswordCheck(check)
                    .stateChange(current.consoleType().name(), target.name())
                    .contextVenue(venueId)
                    .payload(Map.of("venueId", venueId, "previousConsole", current.consoleType().name(),
                            "newConsole", target.name()))
                    .build());
            outbox.publish(context, roomEvent(IntegrationEvents.RESOURCE_CONSOLE_CHANGED, venueId, resourceId));
            return Resources.read(context.jdbc(), resourceId);
        }

        @Override
        public String toString() {
            return "ChangeConsole[resourceId=" + resourceId + ", target=" + target + ", <owner password redacted>]";
        }
    }

    /**
     * The new resource, ACTIVE with no restriction, named with the next number for its activity, archived resources
     * included, so a name is never reused (FR-VEN-41, {@code uq_resource_name}). Its first history row has no "from".
     */
    private static Resources.Resource insert(CommandContext context, UUID venueId, Activity activity,
                                             ConsoleType consoleType, Actor actor, UUID correlationId,
                                             OwnerPasswordCheck check, AuditWriter audit) {
        // fk_resource_venue_activity refuses it too, but its mapping (VENUE_ACTIVITY_IN_USE) is for removing an
        // activity; an activity the venue does not offer is a bad input here.
        boolean offered = context.jdbc().sql("""
                        SELECT EXISTS (SELECT 1 FROM venue.venue_activity
                                       WHERE venue_id = :venue AND activity = :activity)""")
                .param("venue", venueId).param("activity", activity.name()).query(Boolean.class).single();
        if (!offered) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "activity");
        }

        String prefix = prefix(activity) + " ";
        int highest = context.jdbc().sql("""
                        SELECT COALESCE(MAX(substring(name FROM length(:prefix) + 1)::integer), 0)
                          FROM resource.resource
                         WHERE venue_id = :venue AND activity = :activity
                           AND left(name, length(:prefix)) = :prefix
                           AND substring(name FROM length(:prefix) + 1) ~ '^[0-9]{1,9}$'""")
                .param("venue", venueId).param("activity", activity.name()).param("prefix", prefix)
                .query(Integer.class).single();
        String name = prefix + (highest + 1);
        UUID resourceId = UUID.randomUUID();
        String console = consoleType == null ? null : consoleType.name();
        context.jdbc().sql("""
                        INSERT INTO resource.resource(id, venue_id, activity, name, console_type, base_state,
                                                      operational_restriction)
                        VALUES (:id, :venue, :activity, :name, :console, 'ACTIVE', 'NONE')""")
                .param("id", resourceId).param("venue", venueId).param("activity", activity.name())
                .param("name", name).param("console", console).update();
        context.jdbc().sql("""
                        INSERT INTO resource.resource_state_change(
                            id, resource_id, from_base_state, to_base_state, from_restriction, to_restriction,
                            from_console, to_console, owner_password_used, actor_type, actor_id, correlation_id,
                            changed_at)
                        VALUES (:id, :resource, NULL, 'ACTIVE', NULL, 'NONE', NULL, :console, :ownerPasswordUsed,
                                :actorType, :actorId, :correlation, :changedAt)""")
                .param("id", UUID.randomUUID()).param("resource", resourceId).param("console", console)
                .param("ownerPasswordUsed", actor.ownerPasswordUsed()).param("actorType", actor.type())
                .param("actorId", actor.id()).param("correlation", correlationId)
                .param("changedAt", utc(context)).update();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("venueId", venueId);
        payload.put("activity", activity.name());
        payload.put("name", name);
        if (console != null) {
            payload.put("consoleType", console);
        }
        audit.write(context, AuditRecord.builder(ResourceAuditAction.RESOURCE_CREATED, actor.audit(), resourceId,
                correlationId).ownerPasswordCheck(check).stateChange(null, "ACTIVE").contextVenue(venueId)
                .payload(payload).build());
        return Resources.read(context.jdbc(), resourceId);
    }

    /** The resource of the signed-in venue; another venue's resource does not exist for it. */
    private static Resources.Resource ofVenue(CommandContext context, UUID venueId, UUID resourceId) {
        Resources.Resource resource = Resources.read(context.jdbc(), resourceId);
        if (!resource.venueId().equals(venueId)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
        return resource;
    }

    /**
     * FR-VEN-14: refused while any current or future blocking reservation remains; nothing is cancelled. The details
     * name each one so the Dashboard can show what to resolve first (PRD acceptance "names the blocking reservation").
     */
    /**
     * A room event of {@link IntegrationEvents#INVENTORY_AFFECTING}, in the command's transaction: the venue's Nearest
     * Availability rows refresh at once, without waiting for the one-minute pass (E7-56, PO decision 12 Oct 2026).
     */
    private static NewOutboxEvent roomEvent(String type, UUID venueId, UUID resourceId) {
        return new NewOutboxEvent(type, "RESOURCE", resourceId,
                Map.of("venueId", venueId.toString(), "resourceId", resourceId.toString()));
    }

    private static void refuseWhenBlocked(CommandContext context, ActiveCommitments commitments, UUID resourceId) {
        List<ActiveCommitments.Commitment> blocking =
                commitments.blockingResourceChange(context, resourceId, context.commandNow());
        if (blocking.isEmpty()) {
            return;
        }
        List<Map<String, Object>> reservations = blocking.stream().map(b -> Map.<String, Object>of(
                "reservationId", b.reservationId(),
                "reservationType", b.owner().type().name(),
                "ownerId", b.owner().id(),
                "plannedStart", b.plannedStart().toString(),
                "plannedEnd", b.plannedEnd().toString())).toList();
        throw new ApiException(ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS, null,
                Map.of("reservations", reservations));
    }

    static OffsetDateTime utc(CommandContext context) {
        return OffsetDateTime.ofInstant(context.commandNow(), ZoneOffset.UTC);
    }

    private static String prefix(Activity activity) {
        return switch (activity) {
            case PLAYSTATION -> "Room";
            case BILLIARDS -> "Pool Table";
            case PING_PONG -> "Table";
        };
    }
}
