package ps.e7gz.support.fixtures;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;

/**
 * A {@code resource.resource}: an ACTIVE PS5 room with no restriction, named like the services name it (Room n /
 * Pool Table n / Table n, FR-VEN-41), in a new venue unless {@link #at} names one. The venue is made to offer the
 * activity if it does not. Its history gets the creation row and, for another state, one change row
 * ({@code resource.resource_state_change}), as the services write them.
 */
public final class ResourceBuilder {

    private UUID venueId;
    private Activity activity = Activity.PLAYSTATION;
    private ConsoleType consoleType = ConsoleType.PS5;
    private String name;
    private ResourceBaseState baseState = ResourceBaseState.ACTIVE;
    private OperationalRestriction restriction = OperationalRestriction.NONE;

    ResourceBuilder() {
    }

    public ResourceBuilder at(UUID venueId) {
        this.venueId = venueId;
        return this;
    }

    public ResourceBuilder at(Fixtures.Venue venue) {
        return at(venue.id());
    }

    public ResourceBuilder playStation(ConsoleType consoleType) {
        this.activity = Activity.PLAYSTATION;
        this.consoleType = consoleType;
        return this;
    }

    public ResourceBuilder billiards() {
        this.activity = Activity.BILLIARDS;
        this.consoleType = null;
        return this;
    }

    public ResourceBuilder pingPong() {
        this.activity = Activity.PING_PONG;
        this.consoleType = null;
        return this;
    }

    public ResourceBuilder named(String name) {
        this.name = name;
        return this;
    }

    public ResourceBuilder disabled() {
        this.baseState = ResourceBaseState.DISABLED;
        return this;
    }

    public ResourceBuilder archived() {
        this.baseState = ResourceBaseState.ARCHIVED;
        return this;
    }

    /** Not sellable until the owner reviews it, whatever its base state. */
    public ResourceBuilder ownerReviewRequired() {
        this.restriction = OperationalRestriction.OWNER_REVIEW_REQUIRED;
        return this;
    }

    public Fixtures.Resource create() {
        return create(FixtureDb.owner());
    }

    public Fixtures.Resource create(JdbcClient db) {
        UUID venue = venueId != null ? venueId : new VenueBuilder().offering(activity).create(db).id();
        VenueBuilder.offer(db, venue, activity);
        String resourceName = name != null ? name : nextName(db, venue);
        String console = consoleType == null ? null : consoleType.name();
        UUID id = FixtureDb.id();
        db.sql("""
                        INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state,
                                                       operational_restriction)
                        VALUES (:id, :venue, :activity, :name, :console, :state, :restriction)""")
                .param("id", id).param("venue", venue).param("activity", activity.name()).param("name", resourceName)
                .param("console", console).param("state", baseState.name()).param("restriction", restriction.name())
                .update();
        history(db, id, null, ResourceBaseState.ACTIVE, null, OperationalRestriction.NONE, console);
        if (baseState != ResourceBaseState.ACTIVE || restriction != OperationalRestriction.NONE) {
            history(db, id, ResourceBaseState.ACTIVE, baseState, OperationalRestriction.NONE, restriction, null);
        }
        return new Fixtures.Resource(id, venue, activity, resourceName, consoleType);
    }

    private String nextName(JdbcClient db, UUID venue) {
        String prefix = switch (activity) {
            case PLAYSTATION -> "Room ";
            case BILLIARDS -> "Pool Table ";
            case PING_PONG -> "Table ";
        };
        int highest = db.sql("""
                        SELECT COALESCE(MAX(substring(name FROM length(:prefix) + 1)::integer), 0)
                          FROM resource.resource
                         WHERE venue_id = :venue AND activity = :activity
                           AND left(name, length(:prefix)) = :prefix
                           AND substring(name FROM length(:prefix) + 1) ~ '^[0-9]{1,9}$'""")
                .param("venue", venue).param("activity", activity.name()).param("prefix", prefix)
                .query(Integer.class).single();
        return prefix + (highest + 1);
    }

    private static void history(JdbcClient db, UUID resource, ResourceBaseState fromState, ResourceBaseState toState,
                                OperationalRestriction fromRestriction, OperationalRestriction toRestriction,
                                String toConsole) {
        db.sql("""
                        INSERT INTO resource.resource_state_change (id, resource_id, from_base_state, to_base_state,
                                                                    from_restriction, to_restriction, to_console,
                                                                    actor_type, changed_at)
                        VALUES (:id, :resource, :fromState, :toState, :fromRestriction, :toRestriction, :console,
                                'SYSTEM', now())""")
                .param("id", FixtureDb.id()).param("resource", resource)
                .param("fromState", fromState == null ? null : fromState.name())
                .param("toState", toState.name())
                .param("fromRestriction", fromRestriction == null ? null : fromRestriction.name())
                .param("toRestriction", toRestriction.name()).param("console", toConsole).update();
    }
}
