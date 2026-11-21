package ps.e7gz.operations.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerPasswordNotRequired;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * Maintenance writes (FR-OPS-01…FR-OPS-05, Product Spec §39, FRS F-18): each one command with the venue and the room
 * locked. Every window has its {@code operations.maintenance_window} row and, while it counts, one active
 * {@code MAINTENANCE} row in the ledger (arch 05 §3.2, ADR-010 Layer 1, outside {@code ex_no_double_booking}).
 *
 * <p>The application decides; the database refuses a bad write (CLAUDE.md §5, ADR-026). The rolling total is the
 * database's own union ({@code operations.maintenance_candidate_max_rolling_minutes}, O3: back-to-back or overlapping
 * time counts once, every 48-hour window around the candidate is checked, emergency-created windows count, FR-OPS-05),
 * compared with {@link #THRESHOLD_MINUTES}; {@code operations.guard_maintenance_window} re-asserts it, and the overlap
 * with a commitment ({@code gd_maintenance_overlaps_commitment}, mapped to MAINTENANCE_OVERLAPS_RESERVATION).
 */
final class MaintenanceCommands {

    /** FR-OPS-03: more than 24 hours of maintenance inside any rolling 48 hours needs the Owner Password. A constant. */
    static final long THRESHOLD_MINUTES = 24 * 60;

    static final String OVER_THRESHOLD = "MAINTENANCE_OVER_THRESHOLD";
    static final String UNDER_THRESHOLD = "MAINTENANCE_UNDER_THRESHOLD";

    private MaintenanceCommands() {
    }

    /** One window, as stored. */
    record Window(UUID id, UUID resourceId, UUID venueId, Instant startAt, Instant endAt, String reason,
                  UUID createdFromEmergencyId, boolean ownerPasswordUsed) {
    }

    /** What a write needs besides its own fields. */
    record Deps(ReservationLedger ledger, ResourceStates resources, AuditWriter audit,
                Outbox outbox, OwnerPasswordGate gate) {
    }

    /** A new window or an edit's new values; {@code null} start, end or reason in an edit keeps the stored value. */
    record Change(UUID venueId, UUID venueAccountId, UUID correlationId, UUID resourceId, UUID maintenanceId,
                  Instant startAt, Instant endAt, String reason) {
    }

    // ---------------------------------------------------------------------------------------------- the commands

    /** Create or edit without the Owner Password: refused with 423 OWNER_PASSWORD_REQUIRED over the threshold. */
    @OwnerPasswordNotRequired(UNDER_THRESHOLD)
    record Save(Change change, Deps deps) implements Command<Window> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks(change));
        }

        @Override
        public Window execute(CommandContext context) {
            return save(context, change, deps, null);
        }
    }

    /**
     * Create or edit with the Owner Password (FR-OPS-03, FR-OPS-04), checked first in this transaction (FR-VEN-02);
     * sent, it is always checked, whatever the total. Run through {@link OwnerPasswordGate#run(Command, UUID, UUID)}.
     */
    @OwnerProtected(OVER_THRESHOLD)
    record ProtectedSave(Change change, String ownerPassword, Deps deps) implements Command<Window> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks(change));
        }

        @Override
        public Window execute(CommandContext context) {
            OwnerPasswordCheck check = deps.gate().verify(context, change.venueAccountId(), ownerPassword);
            return save(context, change, deps, check);
        }

        /** The password is the gate's alone (NFR-21). */
        @Override
        public String toString() {
            return "ProtectedSave[change=" + change + "]";
        }
    }

    /** Delete a window that has not ended (FR-OPS-01); no Owner Password - deleting never raises the total. */
    record Delete(UUID venueId, UUID venueAccountId, UUID correlationId, UUID resourceId, UUID maintenanceId,
                  Deps deps) implements Command<Void> {

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
            Window window = live(context, venueId, maintenanceId);
            Instant now = context.commandNow();
            deps.ledger().releaseAll(context, ReservationOwner.maintenance(maintenanceId), now, "MAINTENANCE_DELETED");
            context.jdbc().sql("UPDATE operations.maintenance_window SET deleted_at = :now WHERE id = :id")
                    .param("now", utc(now)).param("id", maintenanceId).update();
            deps.audit().write(context, AuditRecord.builder(OperationsAuditAction.MAINTENANCE_DELETED,
                            AuditActor.venue(venueAccountId), maintenanceId, correlationId)
                    .contextVenue(venueId)
                    .payload(Map.of("resourceId", window.resourceId(), "startAt", window.startAt().toString(),
                            "endAt", window.endAt().toString()))
                    .build());
            deps.outbox().publish(context, event(IntegrationEvents.MAINTENANCE_DELETED, window));
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------- shared steps

    private static LockSet locks(Change change) {
        return LockSet.builder().venues(change.venueId()).resources(change.resourceId()).build();
    }

    /**
     * The window as it will be saved, checked in the order of the rules: the room (create) or the window (edit), the
     * interval, the Closure overlap (FR-VEN-33), the rolling threshold (FR-OPS-03/04); then the row, its ledger row,
     * the audit row and the event, all in this transaction (Sprint 2 review, PO decision 12 Oct 2026).
     */
    static Window save(CommandContext context, Change change, Deps deps, OwnerPasswordCheck check) {
        Instant now = context.commandNow();
        Optional<Window> current = Optional.empty();
        if (change.maintenanceId() == null) {
            requireLiveRoom(context, deps, change.venueId(), change.resourceId());
        } else {
            current = Optional.of(live(context, change.venueId(), change.maintenanceId()));
        }
        Instant start = change.startAt() != null ? change.startAt() : current.map(Window::startAt).orElseThrow();
        Instant end = change.endAt() != null ? change.endAt() : current.map(Window::endAt).orElseThrow();
        String reason = change.reason() != null ? change.reason().strip() : current.map(Window::reason).orElseThrow();
        if (reason.isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "reason");
        }
        if (!end.isAfter(start)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "endAt");
        }
        if (!end.isAfter(now)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "endAt"); // a window entirely in the past
        }
        boolean periodChanged = current.map(w -> !w.startAt().equals(start) || !w.endAt().equals(end)).orElse(true);
        boolean ownerPasswordUsed = current.map(Window::ownerPasswordUsed).orElse(false);
        if (periodChanged) {
            // FR-VEN-33: new or extended Maintenance never overlaps a Closure - the Closure's CLOSURE rows refuse it as
            // well, but a room added after the Closure has none, so the application decides from the Closure itself
            if (closureOverlaps(context, change.venueId(), start, end)) {
                throw new ApiException(ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION);
            }
            boolean overThreshold = rollingMinutes(context, change.resourceId(), start, end, change.maintenanceId())
                    .compareTo(BigDecimal.valueOf(THRESHOLD_MINUTES)) > 0;
            if (overThreshold && check == null) {
                throw new ApiException(ApiErrorCode.OWNER_PASSWORD_REQUIRED);
            }
            ownerPasswordUsed = overThreshold;
        }

        UUID id;
        if (current.isEmpty()) {
            id = UUID.randomUUID();
            context.jdbc().sql("""
                            INSERT INTO operations.maintenance_window (id, resource_id, period, reason,
                                                                       created_from_emergency_id, owner_password_used)
                            VALUES (:id, :resource, tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz),
                                    '[)'), :reason, NULL, :used)""")
                    .param("id", id).param("resource", change.resourceId()).param("start", utc(start))
                    .param("end", utc(end)).param("reason", reason).param("used", ownerPasswordUsed).update();
            deps.ledger().create(context, new NewReservation(change.resourceId(), ReservationOwner.maintenance(id),
                    start, end));
        } else {
            id = change.maintenanceId();
            context.jdbc().sql("""
                            UPDATE operations.maintenance_window
                               SET period = tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                   reason = :reason, owner_password_used = :used
                             WHERE id = :id""")
                    .param("id", id).param("start", utc(start)).param("end", utc(end)).param("reason", reason)
                    .param("used", ownerPasswordUsed).update();
            if (periodChanged) {
                // The guard row is immutable (arch 05 §3.2): released and replaced in this transaction (O9)
                deps.ledger().releaseAll(context, ReservationOwner.maintenance(id), now, "MAINTENANCE_EDITED");
                deps.ledger().create(context, new NewReservation(change.resourceId(),
                        ReservationOwner.maintenance(id), start, end));
            }
        }
        Window saved = read(context, change.venueId(), id).orElseThrow();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", saved.resourceId());
        payload.put("startAt", saved.startAt().toString());
        payload.put("endAt", saved.endAt().toString());
        current.ifPresent(before -> {
            payload.put("previousStartAt", before.startAt().toString());
            payload.put("previousEndAt", before.endAt().toString());
        });
        payload.put("ownerPasswordUsed", saved.ownerPasswordUsed());
        AuditRecord.Builder audit = AuditRecord.builder(current.isEmpty() ? OperationsAuditAction.MAINTENANCE_CREATED
                        : OperationsAuditAction.MAINTENANCE_EDITED, AuditActor.venue(change.venueAccountId()), id,
                        change.correlationId())
                .contextVenue(change.venueId())
                .payload(payload);
        if (check != null) {
            audit.ownerPasswordCheck(check);
        }
        deps.audit().write(context, audit.build());
        deps.outbox().publish(context, event(current.isEmpty() ? IntegrationEvents.MAINTENANCE_CREATED
                : IntegrationEvents.MAINTENANCE_EDITED, saved));
        return saved;
    }

    /** The room is the venue's and not ARCHIVED (the contract's NOT_FOUND). */
    private static void requireLiveRoom(CommandContext context, Deps deps, UUID venueId, UUID resourceId) {
        ResourceStates.ResourceState room = deps.resources().find(context, resourceId)
                .filter(state -> state.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (room.baseState() == ResourceBaseState.ARCHIVED) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    /**
     * The venue's window that still counts (not deleted) - else NOT_FOUND - and has not ended at the command instant -
     * else OPERATIONAL_STATE_CHANGED (PO decision 19 Nov 2026, E7-77 D6).
     */
    static Window live(CommandContext context, UUID venueId, UUID maintenanceId) {
        Window window = read(context, venueId, maintenanceId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (!window.endAt().isAfter(context.commandNow())) {
            throw new ApiException(ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }
        return window;
    }

    private static Optional<Window> read(CommandContext context, UUID venueId, UUID maintenanceId) {
        return context.jdbc().sql(MaintenanceWindows.SELECT + """
                         WHERE m.id = :id AND r.venue_id = :venue AND m.deleted_at IS NULL""")
                .param("id", maintenanceId).param("venue", venueId)
                .query(MaintenanceWindows::window).optional();
    }

    /** O3: the largest unioned total of any rolling 48 hours around the candidate, without the window being edited. */
    private static BigDecimal rollingMinutes(CommandContext context, UUID resourceId, Instant start, Instant end,
                                         UUID excluded) {
        return context.jdbc().sql("""
                        SELECT operations.maintenance_candidate_max_rolling_minutes(:resource,
                                   tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                   CAST(:excluded AS uuid))""")
                .param("resource", resourceId).param("start", utc(start)).param("end", utc(end))
                .param("excluded", excluded)
                .query(BigDecimal.class).single();
    }

    /** A Closure of the venue that still blocks overlaps {@code [start, end)} (FR-VEN-33). */
    private static boolean closureOverlaps(CommandContext context, UUID venueId, Instant start, Instant end) {
        return context.jdbc().sql("""
                        SELECT EXISTS (SELECT 1 FROM operations.venue_closure c
                                        WHERE c.venue_id = :venue AND c.deleted_at IS NULL
                                          AND c.period && tstzrange(CAST(:start AS timestamptz),
                                                                    CAST(:end AS timestamptz), '[)'))""")
                .param("venue", venueId).param("start", utc(start)).param("end", utc(end))
                .query(Boolean.class).single();
    }

    /** {@code {venueId, resourceId, maintenanceId}}: the Nearest Availability consumer refreshes the venue at once. */
    private static NewOutboxEvent event(String type, Window window) {
        return new NewOutboxEvent(type, "MAINTENANCE", window.id(), Map.of("venueId", window.venueId().toString(),
                "resourceId", window.resourceId().toString(), "maintenanceId", window.id().toString()));
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
