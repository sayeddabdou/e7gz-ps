package ps.e7gz.resource.internal;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.resource.internal.ResourceCommands.BaseStateAction;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * A venue's rooms and tables (FR-VEN-13…FR-VEN-15, FR-VEN-41): the Platform Admin lists and adds them during onboarding
 * (FR-ADM-02); the venue lists them, adds them and changes their state from the Dashboard with the Owner Password
 * (FR-VEN-03). There is no general edit: console and state have their own actions (PO decision 2 Oct 2026).
 */
@Service
public class Resources {

    private static final String SELECT = """
            SELECT id, venue_id, activity, name, console_type, base_state, operational_restriction
              FROM resource.resource""";

    private final CommandRunner runner;
    private final OwnerPasswordGate gate;
    private final VenueAccountVenues accounts;
    private final ActiveCommitments commitments;
    private final AuditWriter audit;
    private final JdbcClient jdbc;

    public Resources(CommandRunner runner, OwnerPasswordGate gate, VenueAccountVenues accounts,
                     ActiveCommitments commitments, AuditWriter audit, DataSource appDataSource) {
        this.runner = runner;
        this.gate = gate;
        this.accounts = accounts;
        this.commitments = commitments;
        this.audit = audit;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    public record Resource(UUID id, UUID venueId, Activity activity, String name, ConsoleType consoleType,
                           ResourceBaseState baseState, OperationalRestriction operationalRestriction) {
    }

    /** The changes a venue makes to one resource's base state (FR-VEN-03). */
    public enum StateChange {
        DISABLE(BaseStateAction.DISABLE),
        RE_ENABLE(BaseStateAction.RE_ENABLE),
        ARCHIVE(BaseStateAction.ARCHIVE),
        RESTORE(BaseStateAction.RESTORE);

        private final BaseStateAction action;

        StateChange(BaseStateAction action) {
            this.action = action;
        }
    }

    // ------------------------------------------------------------------------------------------ Platform Admin

    public List<Resource> list(UUID venueId) {
        requireVenue(venueId);
        return listOf(venueId);
    }

    public Resource create(UUID adminId, UUID venueId, Activity activity, ConsoleType consoleType) {
        requireVenue(venueId);
        validateConsole(activity, consoleType);
        return runner.run(new ResourceCommands.AdminCreate(adminId, CorrelationId.require(), venueId, activity,
                consoleType, audit));
    }

    // ------------------------------------------------------------------------------------------ Venue Dashboard

    public List<Resource> listForVenue(UUID venueAccountId) {
        return listOf(accounts.venueOf(venueAccountId));
    }

    /** One resource of the signed-in venue; another venue's resource is NOT_FOUND. */
    public Resource getForVenue(UUID venueAccountId, UUID resourceId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        return jdbc.sql(SELECT + " WHERE id = :id AND venue_id = :venue")
                .param("id", resourceId).param("venue", venueId).query(Resources::map).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    public Resource createForVenue(UUID venueAccountId, Activity activity, ConsoleType consoleType,
                                   String ownerPassword) {
        UUID venueId = accounts.venueOf(venueAccountId);
        validateConsole(activity, consoleType);
        UUID correlationId = CorrelationId.require();
        return gate.run(new ResourceCommands.VenueCreate(venueAccountId, venueId, correlationId, activity,
                consoleType, ownerPassword, gate, audit), venueAccountId, correlationId);
    }

    public Resource changeState(UUID venueAccountId, UUID resourceId, StateChange change, String ownerPassword) {
        UUID venueId = accounts.venueOf(venueAccountId);
        UUID correlationId = CorrelationId.require();
        return gate.run(new ResourceCommands.ChangeBaseState(venueAccountId, venueId, resourceId, correlationId,
                change.action, ownerPassword, gate, commitments, audit), venueAccountId, correlationId);
    }

    public Resource changeConsole(UUID venueAccountId, UUID resourceId, ConsoleType target, String ownerPassword) {
        UUID venueId = accounts.venueOf(venueAccountId);
        UUID correlationId = CorrelationId.require();
        return gate.run(new ResourceCommands.ChangeConsole(venueAccountId, venueId, resourceId, correlationId,
                target, ownerPassword, gate, commitments, audit), venueAccountId, correlationId);
    }

    // ------------------------------------------------------------------------------------------ shared

    private List<Resource> listOf(UUID venueId) {
        return jdbc.sql(SELECT + " WHERE venue_id = :venue ORDER BY activity, name, id")
                .param("venue", venueId).query(Resources::map).list();
    }

    private void requireVenue(UUID venueId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM venue.venue WHERE id = :id)")
                .param("id", venueId).query(Boolean.class).single();
        if (!exists) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    /**
     * A request-shape check with a field error, ahead of the command; {@code ck_console_iff_playstation} stays the
     * authority.
     */
    private static void validateConsole(Activity activity, ConsoleType consoleType) {
        boolean valid = activity == Activity.PLAYSTATION ? consoleType != null : consoleType == null;
        if (!valid) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "consoleType");
        }
    }

    static Resource read(JdbcClient jdbc, UUID resourceId) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", resourceId).query(Resources::map).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private static Resource map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String console = rs.getString("console_type");
        return new Resource(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                Activity.valueOf(rs.getString("activity")), rs.getString("name"),
                console == null ? null : ConsoleType.valueOf(console),
                ResourceBaseState.valueOf(rs.getString("base_state")),
                OperationalRestriction.valueOf(rs.getString("operational_restriction")));
    }
}
