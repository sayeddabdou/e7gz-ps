package ps.e7gz.operations.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.internal.MaintenanceCommands.Change;
import ps.e7gz.operations.internal.MaintenanceCommands.Deps;
import ps.e7gz.operations.internal.MaintenanceCommands.Window;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The Venue Dashboard's Maintenance (FR-OPS-01…FR-OPS-05; E7-81, the E7-77 contract): list, create, edit, delete. A
 * request that sends an Owner Password runs the protected command, which checks it first (FR-VEN-02: when sent, it is
 * always checked); one without runs the unprotected command, which answers 423 OWNER_PASSWORD_REQUIRED over the rolling
 * threshold and writes nothing. Maintenance never cancels anything (FR-OPS-02), so no Idempotency-Key is needed (the
 * contract's optional list).
 */
@Service
public class MaintenanceWindows {

    /** The window, its room's venue and its interval; the readers add their own WHERE. */
    static final String SELECT = """
            SELECT m.id, m.resource_id, r.venue_id, lower(m.period) AS start_at, upper(m.period) AS end_at, m.reason,
                   m.created_from_emergency_id, m.owner_password_used
              FROM operations.maintenance_window m
              JOIN resource.resource r ON r.id = m.resource_id
            """;

    private final CommandRunner runner;
    private final OwnerPasswordGate gate;
    private final VenueAccountVenues accounts;
    private final Deps deps;
    private final JdbcClient jdbc;
    private final Clock clock;

    MaintenanceWindows(CommandRunner runner, OwnerPasswordGate gate, VenueAccountVenues accounts,
                       ReservationLedger ledger, ResourceStates resources,
                       AuditWriter audit, Outbox outbox, DataSource appDataSource, Clock clock) {
        this.runner = runner;
        this.gate = gate;
        this.accounts = accounts;
        this.deps = new Deps(ledger, resources, audit, outbox, gate);
        this.jdbc = JdbcClient.create(appDataSource);
        this.clock = clock;
    }

    /** A window as the Dashboard sees it. */
    public record View(UUID id, UUID resourceId, Instant startAt, Instant endAt, String reason,
                       UUID createdFromEmergencyId, boolean ownerPasswordUsed) {

        static View of(Window window) {
            return new View(window.id(), window.resourceId(), window.startAt(), window.endAt(), window.reason(),
                    window.createdFromEmergencyId(), window.ownerPasswordUsed());
        }
    }

    /**
     * The signed-in venue's windows that have not ended, earliest first, deleted ones left out (PO decision 19 Nov 2026,
     * E7-77 D1); only {@code resourceId}'s when it is given.
     */
    public List<View> list(UUID venueAccountId, UUID resourceId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        return jdbc.sql(SELECT + """
                         WHERE r.venue_id = :venue AND m.deleted_at IS NULL AND upper(m.period) > :now
                           AND (CAST(:resource AS uuid) IS NULL OR m.resource_id = CAST(:resource AS uuid))
                         ORDER BY lower(m.period), m.id""")
                .param("venue", venueId).param("now", MaintenanceCommands.utc(clock.instant()))
                .param("resource", resourceId)
                .query(MaintenanceWindows::window).list().stream().map(View::of).toList();
    }

    public View create(UUID venueAccountId, UUID resourceId, Instant startAt, Instant endAt, String reason,
                       String ownerPassword) {
        UUID venueId = accounts.venueOf(venueAccountId);
        return save(new Change(venueId, venueAccountId, CorrelationId.require(), resourceId, null, startAt, endAt,
                reason), ownerPassword);
    }

    /** Omitted values keep the stored ones. */
    public View edit(UUID venueAccountId, UUID maintenanceId, Instant startAt, Instant endAt, String reason,
                     String ownerPassword) {
        UUID venueId = accounts.venueOf(venueAccountId);
        UUID resourceId = resourceOf(venueId, maintenanceId);
        return save(new Change(venueId, venueAccountId, CorrelationId.require(), resourceId, maintenanceId, startAt,
                endAt, reason), ownerPassword);
    }

    public void delete(UUID venueAccountId, UUID maintenanceId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        UUID resourceId = resourceOf(venueId, maintenanceId);
        runner.run(new MaintenanceCommands.Delete(venueId, venueAccountId, CorrelationId.require(), resourceId,
                maintenanceId, deps));
    }

    private View save(Change change, String ownerPassword) {
        Window saved = ownerPassword == null
                ? runner.run(new MaintenanceCommands.Save(change, deps))
                : gate.run(new MaintenanceCommands.ProtectedSave(change, ownerPassword, deps),
                        change.venueAccountId(), change.correlationId());
        return View.of(saved);
    }

    /** The room the command must lock; the command re-reads the window under that lock. */
    private UUID resourceOf(UUID venueId, UUID maintenanceId) {
        return jdbc.sql(SELECT + " WHERE m.id = :id AND r.venue_id = :venue AND m.deleted_at IS NULL")
                .param("id", maintenanceId).param("venue", venueId)
                .query(MaintenanceWindows::window).optional()
                .map(Window::resourceId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    static Window window(ResultSet rs, int row) throws SQLException {
        return new Window(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class),
                rs.getObject("venue_id", UUID.class), rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                rs.getObject("end_at", OffsetDateTime.class).toInstant(), rs.getString("reason"),
                rs.getObject("created_from_emergency_id", UUID.class), rs.getBoolean("owner_password_used"));
    }
}
