package ps.e7gz.resource.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/** Resource writes; every one opens the declared command transaction before business DML (arch 04 §2.1). */
final class ResourceCommands {

    private ResourceCommands() {
    }

    record AdminCreate(UUID adminId, UUID correlationId, UUID venueId, Activity activity,
                       ConsoleType consoleType) implements Command<Resources.Resource> {

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
            boolean offered = context.jdbc().sql("""
                            SELECT EXISTS (SELECT 1 FROM venue.venue_activity
                                           WHERE venue_id = :venue AND activity = :activity)""")
                    .param("venue", venueId).param("activity", activity.name()).query(Boolean.class).single();
            if (!offered) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "activity");
            }

            int highest = context.jdbc().sql("""
                            SELECT COALESCE(MAX((substring(name from '([0-9]+)$'))::integer), 0)
                              FROM resource.resource
                             WHERE venue_id = :venue AND activity = :activity""")
                    .param("venue", venueId).param("activity", activity.name()).query(Integer.class).single();
            String name = prefix(activity) + " " + (highest + 1);
            UUID resourceId = UUID.randomUUID();
            context.jdbc().sql("""
                            INSERT INTO resource.resource(id, venue_id, activity, name, console_type, base_state,
                                                          operational_restriction)
                            VALUES (:id, :venue, :activity, :name, :console, 'ACTIVE', 'NONE')""")
                    .param("id", resourceId).param("venue", venueId).param("activity", activity.name())
                    .param("name", name).param("console", consoleType == null ? null : consoleType.name()).update();
            context.jdbc().sql("""
                            INSERT INTO resource.resource_state_change(
                                id, resource_id, from_base_state, to_base_state, from_restriction, to_restriction,
                                from_console, to_console, owner_password_used, actor_type, actor_id, correlation_id,
                                changed_at)
                            VALUES (:id, :resource, NULL, 'ACTIVE', NULL, 'NONE', NULL, :console, false, 'ADMIN',
                                    :admin, :correlation, :changedAt)""")
                    .param("id", UUID.randomUUID()).param("resource", resourceId)
                    .param("console", consoleType == null ? null : consoleType.name()).param("admin", adminId)
                    .param("correlation", correlationId)
                    .param("changedAt", OffsetDateTime.ofInstant(context.commandNow(), ZoneOffset.UTC)).update();
            return Resources.read(context.jdbc(), resourceId);
        }

        private static String prefix(Activity activity) {
            return switch (activity) {
                case PLAYSTATION -> "Room";
                case BILLIARDS -> "Pool Table";
                case PING_PONG -> "Table";
            };
        }
    }
}
