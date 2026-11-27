package ps.e7gz.operations.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import ps.e7gz.admin.api.AdminAlertKind;
import ps.e7gz.admin.api.AdminAlertSeverity;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.OwnerReviewResolution;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.codes.TournamentResourceRemovalCause;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.operations.api.EmergencyBookings;
import ps.e7gz.operations.api.EmergencyBookings.Live;
import ps.e7gz.operations.internal.EmergencyCommands.Deps;
import ps.e7gz.operations.internal.EmergencyCommands.Emergency;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.tournament.api.LiveTournament;

/**
 * OWNER_REVIEW_REQUIRED (O9; E7-84): its entry cascade and its resolution (arch 06 section 2.7, FRS F-22,
 * FR-OPS-26…FR-OPS-33, FR-OPS-43; Spec v5 section 43; the E7-77 contract, PO decisions 19 Nov 2026).
 *
 * <p><b>Entry</b> is the database's: {@code operations.apply_owner_review_threshold_on_emergency_boundary}
 * ({@code tr_20_apply_owner_review_threshold}) evaluates the rolling 24-in-48-hours maintenance total when a Resource
 * Emergency reaches its expected end (ACTIVE -> AWAITING_CONFIRMATION) or is ENDED, and over it sets the restriction and
 * inserts the open review in the same statement (FR-OPS-26, FR-OPS-27: evaluated before the Emergency's block ends, so
 * the resource is never sellable in between). This class never repeats that rule: right after the status write it
 * looks for a review that was not open before ({@link #afterBoundary}) and, if one appeared, runs the system cascade in
 * the same command - all of it or nothing (FR-OPS-29, section 43.4; {@code operations.assert_owner_review_cascade_complete}
 * checks at commit that nothing is left live):
 * <ul>
 *   <li>every live booking of the Resource, whatever its time - PENDING, AWAITING, CONFIRMED, ACTIVE, Online and
 *       Manual - ended under {@code RESOURCE_OWNER_REVIEW_REQUIRED} through {@link EmergencyBookings#endForOwnerReview}
 *       (actor SYSTEM; cancelled, never paused - FR-OPS-30; a PENDING one's response timer stops and never expires;
 *       a late-T4 booking starts at its startAt then is interrupted - Spec v5 section 31.7, the E7-59/E7-60
 *       carry-forward; an ACTIVE one's attendance follows the evidence - FR-ATT-10, PO decision 15 Nov 2026; every
 *       Booking Fee refund full, to the Wallet or as Points - PO decision 19 Nov 2026);</li>
 *   <li>its Tournaments: a pre-start one CANCELLED under the same reason, an ACTIVE one loses the Resource and is
 *       cancelled only when no operational Resource remains (FR-OPS-28, FR-TRN-27);</li>
 *   <li>its expired requests still before startAt: {@code LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED} with
 *       {@code detailCode RESOURCE_OWNER_REVIEW_REQUIRED} (FR-OPS-32, section 43.6) - they hold no reservation;</li>
 *   <li>one operational-interruption record per booking and Tournament it ended (Spec v5 section 53.2), the SYSTEM audit
 *       row with the threshold data (section 43.9), {@code ResourceRestrictionChanged} for the venue owner and Nearest
 *       Availability, and the Platform Admin's alert; the customers are told by each {@code BookingCancelled} (FR-OPS-29).
 *       Notifications leave after commit, from the outbox.</li>
 * </ul>
 * No Owner Password anywhere in it (FR-OPS-29, the matrix row SYSTEM_OPERATIONAL_CASCADES).
 *
 * <p><b>Resolution</b> (FR-OPS-33): always the Owner Password ({@link Resolve}, {@code RESOLVE_OWNER_REVIEW}); Return to
 * Service, Extend Maintenance, Disable or Archive; refused with {@code 422 OWNER_REVIEW_RESOLUTION_BLOCKED} and
 * {@code details.blockingReservations} while something blocks it (FR-OPS-43); the restriction cleared and the review
 * closed in the same transaction, which the database checks at commit
 * ({@code tr_owner_review_resolution_final_state}). A review no longer open is {@code 409 OPERATIONAL_STATE_CHANGED}
 * (D6).
 */
final class OwnerReviewCommands {

    static final String RESOLVE_PROTECTED = "RESOLVE_OWNER_REVIEW";
    static final String SUBJECT_TYPE = "OWNER_REVIEW";

    /** A whole-resource cascade has no window: its Tournament read spans all time. */
    private static final Instant BEGINNING = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant END_OF_TIME = Instant.parse("9999-01-01T00:00:00Z");

    private OwnerReviewCommands() {
    }

    /** One stored review, with its Resource's venue. */
    record Review(UUID id, UUID resourceId, UUID venueId, Instant enteredAt, String entryCause, Instant resolvedAt,
                  OwnerReviewResolution resolution, UUID correlationId) {

        static Review map(ResultSet rs, int row) throws SQLException {
            OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
            String resolution = rs.getString("resolution");
            return new Review(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class),
                    rs.getObject("venue_id", UUID.class), rs.getObject("entered_at", OffsetDateTime.class).toInstant(),
                    rs.getString("entry_cause"), resolved == null ? null : resolved.toInstant(),
                    resolution == null ? null : OwnerReviewResolution.valueOf(resolution),
                    rs.getObject("correlation_id", UUID.class));
        }
    }

    static final String SELECT = """
            SELECT o.id, o.resource_id, r.venue_id, o.entered_at, o.entry_cause, o.resolved_at, o.resolution,
                   o.correlation_id
              FROM operations.owner_review o
              JOIN resource.resource r ON r.id = o.resource_id
            """;

    /** The Resource's open review ({@code ux_owner_review_open}: at most one). */
    static Optional<Review> open(CommandContext context, UUID resourceId) {
        return context.jdbc().sql(SELECT + " WHERE o.resource_id = :resource AND o.resolved_at IS NULL")
                .param("resource", resourceId).query(Review::map).optional();
    }

    // ---------------------------------------------------------------------------------------------- entry

    /** What an entry cascade reaches on the Resource, read under the command's locks. */
    record Reach(List<Live> live, List<Live> expired, List<LiveTournament> tournaments) {

        /** The Resource, every Online Customer and every Tournament (with its Resources) it would write. */
        void addTo(LockSet.Builder locks) {
            Stream.concat(live.stream(), expired.stream()).forEach(row -> {
                locks.resources(row.resourceId());
                if (row.customerId() != null) {
                    locks.users(row.customerId());
                }
            });
            tournaments.forEach(t -> {
                locks.tournaments(t.id());
                locks.resources(t.resourceIds());
            });
        }

        boolean coveredBy(LockSet locks) {
            return Stream.concat(live.stream(), expired.stream()).allMatch(row ->
                            locks.resources().contains(row.resourceId())
                                    && (row.customerId() == null || locks.users().contains(row.customerId())))
                    && tournaments.stream().allMatch(t -> locks.tournaments().contains(t.id())
                    && locks.resources().containsAll(t.resourceIds()));
        }
    }

    static Reach reach(CommandContext context, Deps deps, UUID venueId, UUID resourceId) {
        return new Reach(deps.bookings().onResource(context, venueId, resourceId),
                deps.bookings().expiredOnResource(context, venueId, resourceId),
                deps.tournaments().liveOnResources(context, List.of(resourceId), BEGINNING, END_OF_TIME));
    }

    /** What the entry cascade ended, for the audit row. */
    record Entry(Review review, List<EmergencyBookings.Outcome> bookings, List<UUID> cancelledTournaments,
                 List<UUID> trimmedTournaments, int lateConfirmEligibilityLost) {
    }

    /**
     * What a Resource Emergency's boundary read and did before its status write: the review open then, and the bookings
     * started first (R1).
     */
    record Before(Optional<Review> open, List<UUID> startedFirst) {

        static final Before NONE = new Before(Optional.empty(), List.of());
    }

    /**
     * Right before a Resource Emergency's status write: the open review, if any; and, when none is open, the Resource's
     * late-T4 bookings started as of their startAt where T4 would have started them
     * ({@link EmergencyBookings#startOverdue}) - the start gates read the current restriction, so a review entered first
     * would leave them undetermined (the E7-59/E7-60 carry-forward: start, then interrupt at the review's time).
     */
    static Before beforeBoundary(CommandContext context, Deps deps, Emergency emergency) {
        if (emergency.resourceId() == null) {
            return Before.NONE;
        }
        Optional<Review> open = open(context, emergency.resourceId());
        List<UUID> started = open.isPresent() ? List.of()
                : deps.bookings().startOverdue(context, emergency.venueId(), emergency.resourceId());
        return new Before(open, started);
    }

    /**
     * Right after a Resource Emergency's status write (to AWAITING_CONFIRMATION or ENDED): when the database's threshold
     * trigger opened a review that was not open before, its cascade runs now, in this command. The reach must
     * lie inside {@code locks} - else {@link EmergencyCommands.StaleLocks}, and the caller plans again.
     *
     * @param before what {@link #beforeBoundary} read and did
     * @return the entry, when this write entered the review
     */
    static Optional<Entry> afterBoundary(CommandContext context, Deps deps, Emergency emergency,
                                         Before before, LockSet locks) {
        if (emergency.resourceId() == null) {
            return Optional.empty();
        }
        Optional<Review> open = open(context, emergency.resourceId());
        if (open.isEmpty() || before.open().map(Review::id).equals(open.map(Review::id))) {
            return Optional.empty();
        }
        Reach reach = reach(context, deps, emergency.venueId(), emergency.resourceId());
        if (!reach.coveredBy(locks)) {
            throw new EmergencyCommands.StaleLocks();
        }
        return Optional.of(cascade(context, deps, open.get(), emergency.id(), reach, before.startedFirst()));
    }

    private static Entry cascade(CommandContext context, Deps deps, Review review, UUID emergencyId, Reach reach,
                                 List<UUID> startedFirst) {
        UUID venueId = review.venueId();
        UUID resourceId = review.resourceId();
        Instant now = context.commandNow();

        List<EmergencyBookings.Outcome> outcomes = deps.bookings().endForOwnerReview(context, venueId,
                reach.live().stream().map(Live::bookingId).toList());

        List<UUID> cancelled = new ArrayList<>();
        List<UUID> trimmed = new ArrayList<>();
        for (LiveTournament tournament : reach.tournaments()) {
            // FR-OPS-28, FR-TRN-27 (E7-94, arch 06 section 3.5): before the start any loss cancels; an ACTIVE
            // Tournament loses the Resource and goes on while one operational Resource remains
            switch (deps.tournaments().loseResource(context, tournament.id(), resourceId,
                    TournamentResourceRemovalCause.RESOURCE_OWNER_REVIEW_REQUIRED)) {
                case CANCELLED -> cancelled.add(tournament.id());
                case CONTINUES -> trimmed.add(tournament.id());
                case NOT_AFFECTED -> { }
            }
        }

        List<UUID> expired = reach.expired().stream().map(Live::bookingId).toList();
        deps.bookings().lateConfirmEligibilityLost(context, venueId, expired);

        // Spec v5 section 53.2: one append-only operational interruption record per booking and Tournament it ended
        UUID correlationId = CorrelationId.require();
        for (EmergencyBookings.Outcome outcome : outcomes) {
            if (outcome.completed()) {
                continue; // its time had passed: completed as T5 would have, nothing interrupted
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("ownerReviewId", review.id().toString());
            evidence.put("emergencyId", emergencyId.toString());
            evidence.put("fromStatus", outcome.from().name());
            evidence.put("terminalReason", outcome.reason().name());
            evidence.put("startedAtStartAt", outcome.startedFirst() || startedFirst.contains(outcome.bookingId()));
            if (outcome.attendance() != null) {
                evidence.put("attendanceResult", outcome.attendance().name());
            }
            boolean online = outcome.source() == BookingSource.ONLINE;
            EmergencyCommands.interruption(context, deps, online ? "BOOKING" : "MANUAL_BOOKING", outcome.bookingId(),
                    outcome.reason().name(), now, evidence, online,
                    online ? outcome.bookingFee() : BigDecimal.ZERO, correlationId);
        }
        for (UUID tournamentId : cancelled) {
            EmergencyCommands.interruption(context, deps, "TOURNAMENT", tournamentId,
                    TournamentTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED.name(), now,
                    Map.of("ownerReviewId", review.id().toString(), "emergencyId", emergencyId.toString()), false,
                    BigDecimal.ZERO, correlationId);
        }

        BigDecimal minutes = context.jdbc().sql("""
                        SELECT operations.maintenance_union_minutes_in_window(:resource,
                                   CAST(:at AS timestamptz) - interval '48 hours', CAST(:at AS timestamptz), NULL, NULL)""")
                .param("resource", resourceId).param("at", MaintenanceCommands.utc(review.enteredAt()))
                .query(BigDecimal.class).single();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", resourceId.toString());
        payload.put("emergencyId", emergencyId.toString());
        payload.put("enteredAt", review.enteredAt().toString());
        payload.put("entryCause", review.entryCause());
        payload.put("maintenanceMinutes", minutes.toPlainString());
        payload.put("cancelledBookings", outcomes.stream().filter(o -> !o.completed() && o.attendance() == null
                && o.source() == BookingSource.ONLINE).count());
        payload.put("cancelledManualBookings", outcomes.stream().filter(o -> !o.completed() && o.attendance() == null
                && o.source() == BookingSource.MANUAL).count());
        payload.put("interruptedBookings", outcomes.stream().filter(o -> !o.completed() && o.attendance() != null)
                .count());
        payload.put("completedBookings", outcomes.stream().filter(EmergencyBookings.Outcome::completed).count());
        payload.put("cancelledTournaments", cancelled.size());
        payload.put("trimmedTournaments", trimmed.size());
        payload.put("lateConfirmEligibilityLost", expired.size());
        deps.audit().write(context, AuditRecord.builder(OperationsAuditAction.OWNER_REVIEW_ENTERED, AuditActor.system(),
                        review.id(), correlationId)
                .contextVenue(venueId)
                .stateChange(OperationalRestriction.NONE.name(), OperationalRestriction.OWNER_REVIEW_REQUIRED.name())
                .payload(payload)
                .build());
        deps.outbox().publish(context, restrictionChanged(review, OperationalRestriction.OWNER_REVIEW_REQUIRED));
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("venueId", venueId.toString());
        alert.put("resourceId", resourceId.toString());
        alert.put("emergencyId", emergencyId.toString());
        alert.put("enteredAt", review.enteredAt().toString());
        alert.put("correlationId", correlationId.toString());
        deps.alerts().raise(context, new NewAdminAlert(AdminAlertKind.RESOURCE_OWNER_REVIEW_REQUIRED,
                AdminAlertSeverity.SEV_2, SUBJECT_TYPE, review.id(), alert, correlationId));
        return new Entry(review, outcomes, List.copyOf(cancelled), List.copyOf(trimmed), expired.size());
    }

    private static NewOutboxEvent restrictionChanged(Review review, OperationalRestriction restriction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("venueId", review.venueId().toString());
        payload.put("resourceId", review.resourceId().toString());
        payload.put("restriction", restriction.name());
        payload.put("ownerReviewId", review.id().toString());
        return new NewOutboxEvent(IntegrationEvents.RESOURCE_RESTRICTION_CHANGED, "RESOURCE", review.resourceId(),
                payload);
    }

    // ---------------------------------------------------------------------------------------------- resolution

    /** The owner's choice (FR-OPS-33); {@code maintenance} with EXTEND_MAINTENANCE only. */
    record Resolution(OwnerReviewResolution action, Instant maintenanceEndAt, String maintenanceReason) {
    }

    /**
     * Resolve with the Owner Password (FR-OPS-33), checked by the gate first in this transaction - before the
     * Idempotency-Key lookup when one is sent (PO decision Q3, 24 Oct 2026). Every attempt is audited.
     */
    @OwnerProtected(RESOLVE_PROTECTED)
    record Resolve(UUID venueId, UUID venueAccountId, UUID correlationId, UUID resourceId, Resolution resolution,
                   Deps deps) implements Command<Review> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).resources(resourceId).build());
        }

        @Override
        public Review execute(CommandContext context) {
            return resolved(context, this, deps.gate().verified(context, venueAccountId));
        }
    }

    /** What the gate is given when an Idempotency-Key wraps {@link Resolve}: adds nothing. */
    @OwnerProtected(RESOLVE_PROTECTED)
    record ResolveGate<R>(Command<R> command) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return command.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return command.scope();
        }

        @Override
        public R execute(CommandContext context) {
            return command.execute(context);
        }
    }

    /**
     * Under the venue and resource locks, in the order of the rules: the venue's resource (else NOT_FOUND); the request's
     * shape (maintenance with EXTEND_MAINTENANCE only, else VALIDATION_FAILED); an open review (else
     * OPERATIONAL_STATE_CHANGED, D6); what blocks the action (FR-OPS-33, FR-OPS-43); then the final resource action, the
     * restriction cleared, the review closed, the audit row with the check.
     */
    private static Review resolved(CommandContext context, Resolve resolve, OwnerPasswordCheck check) {
        Deps deps = resolve.deps();
        Resolution resolution = resolve.resolution();
        UUID resourceId = resolve.resourceId();
        UUID venueId = resolve.venueId();
        ResourceStates.ResourceState room = deps.resources().find(context, resourceId)
                .filter(state -> state.venueId().equals(venueId))
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        boolean extend = resolution.action() == OwnerReviewResolution.EXTEND_MAINTENANCE;
        boolean maintenanceSent = resolution.maintenanceEndAt() != null || resolution.maintenanceReason() != null;
        if (extend != maintenanceSent) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "maintenance");
        }
        Review review = open(context, resourceId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.OPERATIONAL_STATE_CHANGED));
        Instant now = context.commandNow();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", resourceId.toString());
        payload.put("resolution", resolution.action().name());
        switch (resolution.action()) {
            case RETURN_TO_SERVICE -> {
                // section 43.8: no Maintenance blocking now, no Emergency selling block, otherwise operational
                List<Map<String, Object>> blocking = new ArrayList<>(maintenanceNow(context, resourceId, now));
                emergencyBlock(context, resourceId).ifPresent(blocking::add);
                if (!blocking.isEmpty() || room.baseState() != ResourceBaseState.ACTIVE) {
                    throw blocked(blocking);
                }
            }
            case EXTEND_MAINTENANCE -> {
                refuseWhileBlocked(context, deps, resourceId, now, false);
                payload.put("maintenanceId", extendMaintenance(context, resolve, check, now).toString());
            }
            case DISABLE, ARCHIVE -> {
                refuseWhileBlocked(context, deps, resourceId, now, true);
                ResourceBaseState target = resolution.action() == OwnerReviewResolution.DISABLE
                        ? ResourceBaseState.DISABLED : ResourceBaseState.ARCHIVED;
                if (room.baseState() == ResourceBaseState.ARCHIVED
                        || (target == ResourceBaseState.DISABLED && room.baseState() == ResourceBaseState.DISABLED)) {
                    if (room.baseState() != target) {
                        throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "action");
                    }
                } else {
                    deps.baseStates().change(context, resolve.venueAccountId(), venueId, resourceId, target, check,
                            resolve.correlationId());
                }
                payload.put("baseState", target.name());
            }
        }
        deps.baseStates().clearOwnerReviewRestriction(context, resolve.venueAccountId(), venueId, resourceId,
                resolve.correlationId());
        // guard_owner_review_mutation: the one-time Owner-authorised close
        context.jdbc().sql("""
                        UPDATE operations.owner_review
                           SET resolved_at = :now, resolution = :resolution, owner_password_used = true
                         WHERE id = :id AND resolved_at IS NULL""")
                .param("now", MaintenanceCommands.utc(now)).param("resolution", resolution.action().name())
                .param("id", review.id()).update();
        payload.put("resolvedAt", now.toString());
        deps.audit().write(context, AuditRecord.builder(OperationsAuditAction.OWNER_REVIEW_RESOLVED,
                        AuditActor.venue(resolve.venueAccountId()), review.id(), resolve.correlationId())
                .contextVenue(venueId)
                .ownerPasswordCheck(check)
                .stateChange(OperationalRestriction.OWNER_REVIEW_REQUIRED.name(), OperationalRestriction.NONE.name())
                .payload(payload)
                .build());
        return context.jdbc().sql(SELECT + " WHERE o.id = :id").param("id", review.id()).query(Review::map).single();
    }

    /**
     * EXTEND_MAINTENANCE: the Maintenance window in force now runs on to the new end; with none, a new one from now to
     * it. The Maintenance rules apply unchanged (FR-OPS-02, FR-VEN-33: never over a commitment or a Closure); the Owner
     * Password of this resolution authorises it over the rolling threshold (FR-OPS-03).
     */
    private static UUID extendMaintenance(CommandContext context, Resolve resolve, OwnerPasswordCheck check,
                                          Instant now) {
        Deps deps = resolve.deps();
        Optional<UUID> current = context.jdbc().sql("""
                        SELECT m.id FROM operations.maintenance_window m
                         WHERE m.resource_id = :resource AND m.deleted_at IS NULL AND m.period @> CAST(:now AS timestamptz)""")
                .param("resource", resolve.resourceId()).param("now", MaintenanceCommands.utc(now))
                .query(UUID.class).optional();
        Instant end = resolve.resolution().maintenanceEndAt();
        MaintenanceCommands.Change change = new MaintenanceCommands.Change(resolve.venueId(), resolve.venueAccountId(),
                resolve.correlationId(), resolve.resourceId(), current.orElse(null), current.isPresent() ? null : now,
                end, resolve.resolution().maintenanceReason());
        if (current.isPresent()) {
            MaintenanceCommands.Window window = MaintenanceCommands.live(context, resolve.venueId(), current.get());
            if (end == null || !end.isAfter(window.endAt())) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "maintenance.endAt");
            }
        }
        MaintenanceCommands.Deps maintenance = new MaintenanceCommands.Deps(deps.closures().ledger(), deps.resources(),
                deps.audit(), deps.outbox(), deps.gate());
        return MaintenanceCommands.save(context, change, maintenance, Objects.requireNonNull(check, "check")).id();
    }

    /**
     * FR-OPS-43: Disable, Archive and extended Maintenance wait until the Resource's future protected reservations are
     * resolved; Maintenance itself blocks Disable and Archive ({@code blockingResourceChange}, FR-VEN-14) but not more
     * Maintenance.
     */
    private static void refuseWhileBlocked(CommandContext context, Deps deps, UUID resourceId, Instant now,
                                           boolean maintenanceBlocks) {
        List<Map<String, Object>> blocking = deps.commitments().blockingResourceChange(context, resourceId, now)
                .stream()
                .filter(c -> maintenanceBlocks || c.owner().type() != ReservationType.MAINTENANCE)
                .map(OwnerReviewCommands::blockingRow).toList();
        if (!blocking.isEmpty()) {
            throw blocked(blocking);
        }
    }

    private static Map<String, Object> blockingRow(ActiveCommitments.Commitment commitment) {
        return row(commitment.owner().id(), commitment.plannedStart(), commitment.plannedEnd(),
                commitment.owner().type().name());
    }

    /** The live Maintenance windows in force at {@code now} (soft-deleted ones never block, v1.1.44). */
    private static List<Map<String, Object>> maintenanceNow(CommandContext context, UUID resourceId, Instant now) {
        return context.jdbc().sql("""
                        SELECT m.id, lower(m.period) AS start_at, upper(m.period) AS end_at
                          FROM operations.maintenance_window m
                         WHERE m.resource_id = :resource AND m.deleted_at IS NULL
                           AND m.period @> CAST(:now AS timestamptz)
                         ORDER BY lower(m.period)""")
                .param("resource", resourceId).param("now", MaintenanceCommands.utc(now))
                .query((rs, n) -> row(rs.getObject("id", UUID.class),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_at", OffsetDateTime.class).toInstant(), ReservationType.MAINTENANCE.name()))
                .list();
    }

    /** An Emergency selling block in force on the Resource (a new Resource Emergency declared during the review). */
    private static Optional<Map<String, Object>> emergencyBlock(CommandContext context, UUID resourceId) {
        return context.jdbc().sql("""
                        SELECT e.id, e.declared_at, e.expected_end_at
                          FROM resource.resource_selling_block b
                          JOIN operations.emergency e ON e.id = b.emergency_id
                         WHERE b.resource_id = :resource AND b.active""")
                .param("resource", resourceId)
                .query((rs, n) -> row(rs.getObject("id", UUID.class),
                        rs.getObject("declared_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expected_end_at", OffsetDateTime.class).toInstant(), "EMERGENCY"))
                .optional();
    }

    /** One {@code {bookingId, period, kind}} item (arch 09 section 4.2): the blocking owner's id and its interval. */
    private static Map<String, Object> row(UUID id, Instant start, Instant end, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bookingId", id.toString());
        row.put("period", "[" + start + "," + end + ")");
        row.put("kind", kind);
        return row;
    }

    private static ApiException blocked(List<Map<String, Object>> blocking) {
        return new ApiException(ApiErrorCode.OWNER_REVIEW_RESOLUTION_BLOCKED, null,
                Map.of("blockingReservations", List.copyOf(blocking)));
    }
}
