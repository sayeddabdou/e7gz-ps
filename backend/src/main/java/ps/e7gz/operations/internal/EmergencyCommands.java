package ps.e7gz.operations.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.EmergencyConversionTarget;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.codes.EmergencyStatus;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.codes.TournamentResourceRemovalCause;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.codes.VenueClosureKind;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.command.PreviewTokens;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerPasswordNotRequired;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.EmergencyBookings;
import ps.e7gz.operations.api.EmergencyBookings.Live;
import ps.e7gz.operations.internal.ClosureCommands.Affected;
import ps.e7gz.operations.internal.ClosureCommands.Impact;
import ps.e7gz.resource.api.ResourceBaseStateChanges;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.tournament.api.LiveTournament;
import ps.e7gz.tournament.api.TournamentCascades;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * Venue Emergency Closures and resource Emergency Outages (O7/O8; E7-82): declare, extend, end and convert, and the
 * expected end passing (arch 06 section 2.5, FRS F-19, F-20, FR-OPS-06…FR-OPS-25, FR-OPS-44; Spec v5 sections 38, 40-42;
 * the E7-77 contract D1-D6, PO decisions 19 Nov 2026).
 *
 * <p><b>The cascade</b> (FR-OPS-08, FR-OPS-09, FR-OPS-12): over {@code [now, expectedEnd)} - the declared window, never
 * beyond it (FR-OPS-08, FR-OPS-17) - in ONE command transaction, all of it or nothing: every live booking of the venue (or
 * of the one resource) ended through {@link EmergencyBookings#end} under {@code VENUE_EMERGENCY_CLOSURE} /
 * {@code RESOURCE_EMERGENCY_OUTAGE}; then the Emergency row and its selling block (FR-OPS-15: venue block for a venue
 * Emergency, that resource's block only for a resource one - the other resources keep selling, FR-OPS-12); then the
 * live Tournaments (venue: cancelled; resource: pre-start cancelled, an ACTIVE one loses the resource and is cancelled
 * only when no operational resource remains, FR-TRN-27); then the venue-response and Late Confirm timers of the pending
 * requests outside the window paused, each with its open pause-history row (FR-OPS-34, FR-OPS-35) and each decided at
 * once - cap, feasibility, resume - by {@link TimerPauses} (E7-83); one operational-interruption record per booking and Tournament it ended (Spec v5 section
 * 53.2); the expected-end job; the audit row and the Emergency event (Sprint 2 review, PO decision 12 Oct 2026: the
 * Nearest Availability consumer refreshes the venue at once). Notifications leave only after commit, from the outbox
 * (FR-NTF-12). The bookings go first because a late-T4 booking is judged as of its {@code startAt} (Spec v5 section
 * 31.7) - before this Emergency exists; the Tournaments after, because the database accepts a condition-driven
 * Tournament reason only once its cause is written (FR-TRN-31). The database checks the whole at commit
 * ({@code operations.assert_emergency_cascade_complete}, {@code assert_emergency_block_consistency}).
 *
 * <p><b>Locks</b>: the venue, every resource the cascade touches (all of the venue's for a venue Emergency, as the
 * database's own lock order), every affected Customer and Tournament (arch 04 section 2.1). They are found first by a
 * read-only {@link Plan}; the write re-reads under them and, when the set grew meanwhile, a declaration plans again
 * ({@link StaleLocks}) while an extension or conversion answers {@code 409 AFFECTED_SET_CHANGED_REFRESH_PREVIEW}.
 *
 * <p>No Owner Password to declare, extend or end (FR-OPS-06, FR-OPS-11); a conversion obeys its target's own rule
 * (FR-OPS-23). Every write is Idempotency-Key bound by the caller (ADR-015).
 */
final class EmergencyCommands {

    private EmergencyCommands() {
    }

    static final String EXPECTED_END_JOB = "EMERGENCY_EXPECTED_END";
    static final String SUBJECT_TYPE = "EMERGENCY";
    static final String BLOCK_CAUSE = "EMERGENCY";

    static final String CLOSURE_PROTECTED = ClosureCommands.PROTECTED_ACTION;
    static final String MAINTENANCE_PROTECTED = MaintenanceCommands.OVER_THRESHOLD;
    static final String RESOURCE_PROTECTED = "RESOURCE_DISABLE_REENABLE_ARCHIVE_RESTORE";

    private static final Set<EmergencyStatus> UNRESOLVED = EnumSet.of(EmergencyStatus.ACTIVE,
            EmergencyStatus.AWAITING_CONFIRMATION);

    /** One stored Emergency. */
    record Emergency(UUID id, EmergencyScope scope, UUID venueId, UUID resourceId, EmergencyStatus status,
                     Instant declaredAt, Instant expectedEndAt, String reason, EmergencyConversionTarget convertedTo,
                     Instant endedAt) {

        boolean unresolved() {
            return UNRESOLVED.contains(status);
        }

        BookingTerminalReason bookingReason() {
            return reasonOf(scope);
        }

        static Emergency map(ResultSet rs, int row) throws SQLException {
            String converted = rs.getString("converted_to");
            OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
            return new Emergency(rs.getObject("id", UUID.class), EmergencyScope.valueOf(rs.getString("scope")),
                    rs.getObject("venue_id", UUID.class), rs.getObject("resource_id", UUID.class),
                    EmergencyStatus.valueOf(rs.getString("status")),
                    rs.getObject("declared_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("expected_end_at", OffsetDateTime.class).toInstant(), rs.getString("reason"),
                    converted == null ? null : EmergencyConversionTarget.valueOf(converted),
                    ended == null ? null : ended.toInstant());
        }
    }

    static final String SELECT = """
            SELECT e.id, e.scope, e.venue_id, e.resource_id, e.status, e.declared_at, e.expected_end_at, e.reason,
                   e.converted_to, e.ended_at
              FROM operations.emergency e
            """;

    static BookingTerminalReason reasonOf(EmergencyScope scope) {
        return scope == EmergencyScope.VENUE ? BookingTerminalReason.VENUE_EMERGENCY_CLOSURE
                : BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE;
    }

    /** What the commands need besides their own fields. */
    record Deps(EmergencyBookings bookings, TournamentCascades tournaments, ResourceStates resources,
                ResourceCatalog catalog, OperatingSessions sessions, ActiveCommitments commitments,
                ResourceBaseStateChanges baseStates, AuditWriter audit, Outbox outbox, ScheduledJobs jobs,
                OwnerPasswordGate gate, ClosureCommands.Deps closures, ObjectMapper json, TimerPauses timers,
                AdminAlerts alerts) {
    }

    /** The write's locks no longer cover what the cascade reaches: a declaration plans again (never shown). */
    static final class StaleLocks extends RuntimeException {

        StaleLocks() {
            super("The Emergency cascade reaches rows outside its lock set", null, false, false);
        }
    }

    /** The subject a cascade runs over. */
    record Subject(UUID venueId, EmergencyScope scope, UUID resourceId) {

        Subject {
            Objects.requireNonNull(venueId, "venueId");
            Objects.requireNonNull(scope, "scope");
            if ((scope == EmergencyScope.RESOURCE) != (resourceId != null)) {
                throw new IllegalArgumentException(scope + " with resourceId " + resourceId);
            }
        }

        static Subject of(Emergency emergency) {
            return new Subject(emergency.venueId(), emergency.scope(), emergency.resourceId());
        }
    }

    /** What a cascade over a window reaches, read under the command's locks. */
    record Reach(List<Live> ending, List<Live> outside, List<LiveTournament> tournaments) {

        /** The venue, the resources, every Online Customer and every Tournament it would write (arch 04 section 2.1). */
        LockSet lockSet(Subject subject, Collection<UUID> venueResources) {
            LockSet.Builder locks = LockSet.builder().venues(subject.venueId());
            if (subject.scope() == EmergencyScope.VENUE) {
                locks.resources(venueResources);
            } else {
                locks.resources(subject.resourceId());
            }
            Stream.concat(ending.stream(), outside.stream()).forEach(row -> {
                locks.resources(row.resourceId());
                if (row.customerId() != null) {
                    locks.users(row.customerId());
                }
            });
            tournaments.forEach(t -> {
                locks.tournaments(t.id());
                locks.resources(t.resourceIds());
            });
            return locks.build();
        }

        boolean coveredBy(LockSet locks) {
            return Stream.concat(ending.stream(), outside.stream()).allMatch(row ->
                            locks.resources().contains(row.resourceId())
                                    && (row.customerId() == null || locks.users().contains(row.customerId())))
                    && tournaments.stream().allMatch(t -> locks.tournaments().contains(t.id()));
        }
    }

    static Reach reach(CommandContext context, Deps deps, Subject subject, Instant from, Instant to) {
        List<Live> ending = deps.bookings().overlapping(context, subject.venueId(), subject.resourceId(), from, to);
        List<Live> outside = deps.bookings().outsideTheWindow(context, subject.venueId(), subject.resourceId(),
                from, to);
        List<LiveTournament> tournaments = subject.scope() == EmergencyScope.VENUE
                ? deps.tournaments().liveAtVenue(context, subject.venueId(), from, to)
                : deps.tournaments().liveOnResources(context, List.of(subject.resourceId()), from, to);
        return new Reach(ending, outside, tournaments);
    }

    static List<UUID> venueResources(CommandContext context, Deps deps, UUID venueId) {
        return deps.catalog().ofVenue(context, venueId).stream().map(ResourceCatalog.Entry::resourceId).toList();
    }

    // ---------------------------------------------------------------------------------------------- planning

    /**
     * Read-only: the lock set a write over {@code [from, to)} needs. {@code emergencyId} names the Emergency an
     * extension or conversion acts on - its subject and declared start; a declaration passes its subject and now.
     */
    record Plan(UUID venueId, Subject subject, UUID emergencyId, Instant to, Deps deps) implements Command<LockSet> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public LockSet execute(CommandContext context) {
            Subject over = subject;
            Instant from = context.commandNow();
            Instant until = to;
            if (emergencyId != null) {
                Optional<Emergency> found = read(context, venueId, emergencyId);
                if (found.isEmpty()) {
                    return LockSet.builder().venues(venueId).build();
                }
                over = Subject.of(found.get());
                from = found.get().declaredAt();
                if (until == null || !until.isAfter(from)) {
                    until = found.get().expectedEndAt().isAfter(from) ? found.get().expectedEndAt() : from.plusSeconds(1);
                }
            }
            if (over.scope() == EmergencyScope.RESOURCE && deps.resources().find(context, over.resourceId())
                    .filter(room -> room.venueId().equals(venueId)).isEmpty()) {
                return LockSet.builder().venues(venueId).build();
            }
            if (until == null || !until.isAfter(from)) {
                return LockSet.builder().venues(venueId).resources(venueResources(context, deps, venueId)).build();
            }
            return reach(context, deps, over, from, until).lockSet(over, venueResources(context, deps, venueId));
        }
    }

    // ---------------------------------------------------------------------------------------------- declare

    /** A declaration (FR-OPS-06, FR-OPS-11): starts at the command instant, never scheduled. */
    record Declaration(Subject subject, UUID venueAccountId, UUID correlationId, String reason, Instant expectedEndAt,
                       LockSet locks, Deps deps) {
    }

    /** Venue Emergency Closure: no Owner Password (FRS section 2.4 "Emergency Closure"). */
    @OwnerPasswordNotRequired("EMERGENCY_CLOSURE")
    record DeclareVenue(Declaration declaration) implements Command<Emergency> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(declaration.locks());
        }

        @Override
        public Emergency execute(CommandContext context) {
            return declared(context, declaration);
        }
    }

    /** Resource Emergency Outage: no Owner Password (FRS section 2.4 "Resource Emergency Outage"). */
    @OwnerPasswordNotRequired("RESOURCE_EMERGENCY_OUTAGE")
    record DeclareResource(Declaration declaration) implements Command<Emergency> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(declaration.locks());
        }

        @Override
        public Emergency execute(CommandContext context) {
            return declared(context, declaration);
        }
    }

    private static Emergency declared(CommandContext context, Declaration declaration) {
        Deps deps = declaration.deps();
        Subject subject = declaration.subject();
        UUID venueId = subject.venueId();
        Instant now = context.commandNow();
        String reason = declaration.reason() == null ? "" : declaration.reason().strip();
        if (reason.isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "reason");
        }
        if (subject.scope() == EmergencyScope.RESOURCE) {
            ResourceStates.ResourceState room = deps.resources().find(context, subject.resourceId())
                    .filter(state -> state.venueId().equals(venueId))
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (room.baseState() == ResourceBaseState.ARCHIVED) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
        }
        // ux_venue_block_active / ux_resource_block_active: one active block per subject (FR-OPS-15)
        if (blocked(context, subject)) {
            throw new ApiException(subject.scope() == EmergencyScope.VENUE ? ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE
                    : ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
        }
        Instant expectedEnd = declaration.expectedEndAt();
        requireInsideSession(context, deps, venueId, now, expectedEnd);

        Reach reach = reach(context, deps, subject, now, expectedEnd);
        if (!reach.coveredBy(declaration.locks())) {
            throw new StaleLocks();
        }
        Cascade cascade = cascadeBookings(context, deps, subject, reach, declaration.venueAccountId());

        UUID id = UUID.randomUUID();
        context.jdbc().sql("""
                        INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                          expected_end_at, declared_period, reason)
                        VALUES (:id, :scope, :venue, :resource, 'ACTIVE', :now, :end,
                                tstzrange(CAST(:now AS timestamptz), CAST(:end AS timestamptz), '[)'), :reason)""")
                .param("id", id).param("scope", subject.scope().name()).param("venue", venueId)
                .param("resource", subject.resourceId()).param("now", MaintenanceCommands.utc(now))
                .param("end", MaintenanceCommands.utc(expectedEnd)).param("reason", reason).update();
        if (subject.scope() == EmergencyScope.VENUE) {
            context.jdbc().sql("""
                            INSERT INTO venue.venue_selling_block (id, venue_id, cause, emergency_id, active, started_at)
                            VALUES (:block, :venue, :cause, :id, true, :now)""")
                    .param("block", UUID.randomUUID()).param("venue", venueId).param("cause", BLOCK_CAUSE)
                    .param("id", id).param("now", MaintenanceCommands.utc(now)).update();
        } else {
            context.jdbc().sql("""
                            INSERT INTO resource.resource_selling_block (id, resource_id, cause, emergency_id, active,
                                                                         started_at)
                            VALUES (:block, :resource, :cause, :id, true, :now)""")
                    .param("block", UUID.randomUUID()).param("resource", subject.resourceId())
                    .param("cause", BLOCK_CAUSE).param("id", id).param("now", MaintenanceCommands.utc(now)).update();
        }
        Emergency emergency = read(context, venueId, id).orElseThrow();
        cascade = cascadeRest(context, deps, emergency, reach, cascade);
        scheduleExpectedEnd(context, deps, emergency);

        Map<String, Object> payload = cascade.audit();
        payload.put("scope", emergency.scope().name());
        if (emergency.resourceId() != null) {
            payload.put("resourceId", emergency.resourceId().toString());
        }
        payload.put("declaredAt", emergency.declaredAt().toString());
        payload.put("expectedEndAt", emergency.expectedEndAt().toString());
        deps.audit().write(context, AuditRecord.builder(OperationsAuditAction.EMERGENCY_DECLARED,
                        AuditActor.venue(declaration.venueAccountId()), id, declaration.correlationId())
                .contextVenue(venueId)
                .reason(reason)
                .payload(payload)
                .build());
        publish(context, deps, IntegrationEvents.EMERGENCY_DECLARED, emergency);
        return emergency;
    }

    // ---------------------------------------------------------------------------------------------- extend

    /** The impact of a later expected end, bound to it by a token (FR-OPS-22; the E7-77 contract D2). */
    record ExtensionImpact(String previewToken, List<Live> affected, Reach reach) {
    }

    /** The extension preview: writes nothing. */
    record PreviewExtension(UUID venueId, UUID emergencyId, Instant expectedEndAt, Deps deps)
            implements Command<ExtensionImpact> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public ExtensionImpact execute(CommandContext context) {
            return extension(context, deps, unresolved(context, venueId, emergencyId), expectedEndAt);
        }
    }

    /**
     * The checks in the order the rules give them, then the impact: the Emergency unresolved (else
     * OPERATIONAL_STATE_CHANGED, D6); the new expected end later than the current one and than now, and inside the
     * Operating Session the Emergency was declared in (FR-OPS-07, FR-OPS-22: emergency mode is never extended beyond it
     * as an unprotected cancellation mechanism; the database holds the same bound); then the live bookings and
     * Tournaments the longer declared window {@code [declaredAt, newEnd)} reaches - previously cancelled ones are gone
     * and stay cancelled (FR-OPS-10).
     */
    static ExtensionImpact extension(CommandContext context, Deps deps, Emergency emergency, Instant expectedEnd) {
        Instant now = context.commandNow();
        if (expectedEnd == null || !expectedEnd.isAfter(emergency.expectedEndAt()) || !expectedEnd.isAfter(now)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "expectedEndAt");
        }
        requireInsideSession(context, deps, emergency.venueId(), emergency.declaredAt(), expectedEnd);
        Reach reach = reach(context, deps, Subject.of(emergency), emergency.declaredAt(), expectedEnd);
        StringBuilder canonical = new StringBuilder("emergency-extension|").append(emergency.id()).append('|')
                .append(expectedEnd);
        reach.ending().forEach(row -> canonical.append("|b:").append(row.bookingId()));
        reach.tournaments().forEach(t -> canonical.append("|t:").append(t.id()));
        return new ExtensionImpact(PreviewTokens.of(canonical.toString()), reach.ending(), reach);
    }

    record Extend(UUID venueId, UUID venueAccountId, UUID correlationId, UUID emergencyId, Instant expectedEndAt,
                  String previewToken, LockSet locks, Deps deps) implements Command<Emergency> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public Emergency execute(CommandContext context) {
            Emergency before = unresolved(context, venueId, emergencyId);
            ExtensionImpact impact = extension(context, deps, before, expectedEndAt);
            if (!impact.previewToken().equals(previewToken) || !impact.reach().coveredBy(locks)) {
                throw new ApiException(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW);
            }
            Cascade cascade = cascadeBookings(context, deps, Subject.of(before), impact.reach(), venueAccountId);
            // AWAITING_CONFIRMATION returns to ACTIVE only through a valid extension (guard_emergency_lifecycle)
            context.jdbc().sql("""
                            UPDATE operations.emergency SET expected_end_at = :end, status = 'ACTIVE'
                             WHERE id = :id""")
                    .param("end", MaintenanceCommands.utc(expectedEndAt)).param("id", emergencyId).update();
            Emergency emergency = read(context, venueId, emergencyId).orElseThrow();
            cascade = cascadeRest(context, deps, emergency, impact.reach(), cascade);
            deps.jobs().supersede(context, expectedEndKey(before));
            scheduleExpectedEnd(context, deps, emergency);

            Map<String, Object> payload = cascade.audit();
            payload.put("scope", emergency.scope().name());
            if (emergency.resourceId() != null) {
                payload.put("resourceId", emergency.resourceId().toString());
            }
            payload.put("previousExpectedEndAt", before.expectedEndAt().toString());
            payload.put("expectedEndAt", emergency.expectedEndAt().toString());
            deps.audit().write(context, AuditRecord.builder(OperationsAuditAction.EMERGENCY_EXTENDED,
                            AuditActor.venue(venueAccountId), emergencyId, correlationId)
                    .contextVenue(venueId)
                    .payload(payload)
                    .build());
            publish(context, deps, IntegrationEvents.EMERGENCY_EXTENDED, emergency);
            return emergency;
        }
    }

    // ---------------------------------------------------------------------------------------------- end

    /**
     * END EMERGENCY (FR-OPS-19…FR-OPS-21): the staff's audited assertion that the venue or resource is operational again.
     * The Emergency ENDED and its selling block closed at the same instant (the database's ended_at), in one commit - no
     * sellable gap. A resource that is not ACTIVE (disabled or archived meanwhile, FR-OPS-42) cannot return to service:
     * EMERGENCY_CANNOT_END_RESOURCE_NOT_OPERATIONAL (FR-OPS-20) - convert it instead. Nothing cancelled is restored
     * (FR-OPS-10). The rolling maintenance threshold at the end (Owner Review entry, FR-OPS-27) is the database's
     * ({@code tr_20_apply_owner_review_threshold}, in the status write itself, so the resource is never sellable in
     * between); E7-84: when it entered a review, its system cascade runs here in the same command
     * ({@link OwnerReviewCommands#afterBoundary}), before the timers. E7-83: every timer the Emergency paused is decided
     * again at the same instant ({@link TimerPauses#afterResolution}) - resumed with its remaining time when FR-OPS-39
     * holds, else it waits for the next valid intake window, the cap or infeasibility.
     */
    record End(UUID venueId, UUID venueAccountId, UUID correlationId, UUID emergencyId, LockSet locks, Deps deps)
            implements Command<Emergency> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public Emergency execute(CommandContext context) {
            Emergency before = unresolved(context, venueId, emergencyId);
            if (before.scope() == EmergencyScope.RESOURCE) {
                ResourceStates.ResourceState room = deps.resources().find(context, before.resourceId()).orElseThrow();
                if (room.baseState() != ResourceBaseState.ACTIVE) {
                    throw new ApiException(ApiErrorCode.EMERGENCY_CANNOT_END_RESOURCE_NOT_OPERATIONAL);
                }
            }
            OwnerReviewCommands.Before reviewBefore = OwnerReviewCommands.beforeBoundary(context, deps, before);
            Emergency emergency = resolve(context, deps, before, EmergencyStatus.ENDED, null);
            OwnerReviewCommands.afterBoundary(context, deps, emergency, reviewBefore, locks);
            deps.timers().afterResolution(context, venueId, emergency.resourceId(), locks);
            deps.audit().write(context, AuditRecord.builder(OperationsAuditAction.EMERGENCY_ENDED,
                            AuditActor.venue(venueAccountId), emergencyId, correlationId)
                    .contextVenue(venueId)
                    .stateChange(before.status().name(), EmergencyStatus.ENDED.name())
                    .payload(subjectPayload(emergency, Map.of("endedAt", emergency.endedAt().toString())))
                    .build());
            publish(context, deps, IntegrationEvents.EMERGENCY_ENDED, emergency);
            return emergency;
        }
    }

    // ---------------------------------------------------------------------------------------------- convert

    /** The replacement a conversion asks for (FR-OPS-23; the E7-77 contract D2, D5). */
    record Conversion(EmergencyConversionTarget target, VenueClosureKind closureKind, Instant endAt, String reason) {
    }

    /** What a conversion would do, bound to it by a token. */
    record ConversionImpact(String previewToken, List<Affected> affected, boolean ownerPasswordRequired,
                            Impact closureImpact, boolean overThreshold) {

        /** The venue, every affected room and Online customer - as a Closure save (arch 04 section 2.1). */
        LockSet lockSet(Emergency emergency) {
            LockSet.Builder locks = LockSet.builder().venues(emergency.venueId());
            if (emergency.resourceId() != null) {
                locks.resources(emergency.resourceId());
            }
            affected.forEach(row -> {
                locks.resources(row.resourceId());
                if (row.customerId() != null) {
                    locks.users(row.customerId());
                }
            });
            return locks.build();
        }
    }

    record PreviewConversion(UUID venueId, UUID emergencyId, Conversion conversion, Deps deps)
            implements Command<ConversionImpact> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public ConversionImpact execute(CommandContext context) {
            return EmergencyCommands.conversion(context, deps, unresolved(context, venueId, emergencyId), conversion);
        }
    }

    /**
     * The target's own rules (FR-OPS-23): a venue Emergency becomes a normal Closure from now to {@code endAt} - its
     * affected set and Owner Password as a Closure's (FR-VEN-29…FR-VEN-32; OPENING_HOURS_CHANGE goes through the hours
     * commit, D5); a resource Emergency becomes Maintenance from now to {@code endAt} - never over a reservation or a
     * Closure (FR-OPS-02, FR-VEN-33), the Owner Password over the rolling threshold (FR-OPS-03, FR-OPS-11) - or the
     * resource Disabled or Archived - refused while blocking reservations remain (FR-VEN-14, FR-OPS-43), always with the
     * Owner Password. Any other target is CONVERSION_TARGET_NOT_ALLOWED; a missing or extra part VALIDATION_FAILED.
     */
    static ConversionImpact conversion(CommandContext context, Deps deps, Emergency emergency, Conversion conversion) {
        EmergencyConversionTarget target = conversion.target();
        boolean venueTarget = target == EmergencyConversionTarget.CLOSURE;
        boolean resourceTarget = target == EmergencyConversionTarget.MAINTENANCE
                || target == EmergencyConversionTarget.DISABLED || target == EmergencyConversionTarget.ARCHIVED;
        if (emergency.scope() == EmergencyScope.VENUE ? !venueTarget : !resourceTarget) {
            throw new ApiException(ApiErrorCode.CONVERSION_TARGET_NOT_ALLOWED);
        }
        Instant now = context.commandNow();
        StringBuilder canonical = new StringBuilder("emergency-conversion|").append(emergency.id()).append('|')
                .append(target);
        return switch (target) {
            case CLOSURE -> {
                ClosureCommands.Proposal proposal = new ClosureCommands.Proposal(conversion.closureKind(), now,
                        conversion.endAt(), conversion.reason());
                Impact impact = ClosureCommands.impact(context, emergency.venueId(), null, proposal, deps.closures());
                if (impact.awaitingTimeout()) {
                    throw new ApiException(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW);
                }
                canonical.append('|').append(conversion.closureKind()).append('|').append(conversion.endAt())
                        .append('|').append(proposal.reason());
                impact.affected().forEach(row -> canonical.append("|b:").append(row.bookingId()));
                yield new ConversionImpact(PreviewTokens.of(canonical.toString()), impact.affected(),
                        impact.ownerPasswordRequired(), impact, false);
            }
            case MAINTENANCE -> {
                String reason = conversion.reason() == null ? "" : conversion.reason().strip();
                if (reason.isEmpty()) {
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "maintenance.reason");
                }
                if (conversion.endAt() == null || !conversion.endAt().isAfter(now)) {
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "maintenance.endAt");
                }
                // FR-OPS-02, FR-VEN-33: Maintenance never overlaps a commitment or a Closure; it cancels nothing
                boolean overlaps = deps.commitments().overlapping(context, emergency.venueId(), now, conversion.endAt())
                        .stream().anyMatch(row -> row.resourceId().equals(emergency.resourceId())
                                && row.owner().type() != ps.e7gz.common.codes.ReservationType.MAINTENANCE);
                if (overlaps || closureOverlaps(context, emergency.venueId(), now, conversion.endAt())) {
                    throw new ApiException(ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION);
                }
                boolean overThreshold = context.jdbc().sql("""
                                SELECT operations.maintenance_candidate_max_rolling_minutes(:resource,
                                           tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                           NULL)""")
                        .param("resource", emergency.resourceId()).param("start", MaintenanceCommands.utc(now))
                        .param("end", MaintenanceCommands.utc(conversion.endAt()))
                        .query(BigDecimal.class).single()
                        .compareTo(BigDecimal.valueOf(MaintenanceCommands.THRESHOLD_MINUTES)) > 0;
                canonical.append('|').append(conversion.endAt()).append('|').append(reason).append('|')
                        .append(overThreshold);
                yield new ConversionImpact(PreviewTokens.of(canonical.toString()), List.of(), overThreshold, null,
                        overThreshold);
            }
            case DISABLED, ARCHIVED -> {
                ResourceStates.ResourceState room = deps.resources().find(context, emergency.resourceId())
                        .orElseThrow();
                boolean fits = target == EmergencyConversionTarget.DISABLED
                        ? room.baseState() == ResourceBaseState.ACTIVE
                        : room.baseState() != ResourceBaseState.ARCHIVED;
                if (!fits) {
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "target");
                }
                List<ActiveCommitments.Commitment> blocking = deps.commitments()
                        .blockingResourceChange(context, emergency.resourceId(), now);
                if (!blocking.isEmpty()) {
                    throw new ApiException(ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS);
                }
                yield new ConversionImpact(PreviewTokens.of(canonical.toString()), List.of(), true, null, false);
            }
            case OPENING_HOURS_CHANGE -> throw new ApiException(ApiErrorCode.CONVERSION_TARGET_NOT_ALLOWED);
        };
    }

    /** The conversion save; {@code check} is the Owner Password check of a protected run, else null. */
    record Convert(UUID venueId, UUID venueAccountId, UUID correlationId, UUID emergencyId, Conversion conversion,
                   String previewToken, LockSet locks, Deps deps) implements Command<Emergency> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public Emergency execute(CommandContext context) {
            return converted(context, this, null);
        }
    }

    /** A conversion into a Closure that cancels a protected booking (FRS section 2.4, as the Closure save). */
    @OwnerProtected(CLOSURE_PROTECTED)
    record ProtectedClosureConvert(Convert convert) implements Command<Emergency> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return convert.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return convert.scope();
        }

        @Override
        public Emergency execute(CommandContext context) {
            return converted(context, convert, convert.deps().gate().verified(context, convert.venueAccountId()));
        }
    }

    /** A conversion into Maintenance over the rolling threshold (FR-OPS-11: normal maintenance authorization). */
    @OwnerProtected(MAINTENANCE_PROTECTED)
    record ProtectedMaintenanceConvert(Convert convert) implements Command<Emergency> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return convert.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return convert.scope();
        }

        @Override
        public Emergency execute(CommandContext context) {
            return converted(context, convert, convert.deps().gate().verified(context, convert.venueAccountId()));
        }
    }

    /** A conversion into a Disabled or Archived resource (FR-OPS-23: the resource's own rule). */
    @OwnerProtected(RESOURCE_PROTECTED)
    record ProtectedResourceConvert(Convert convert) implements Command<Emergency> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return convert.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return convert.scope();
        }

        @Override
        public Emergency execute(CommandContext context) {
            return converted(context, convert, convert.deps().gate().verified(context, convert.venueAccountId()));
        }
    }

    /** What the gate is given for a Closure conversion: the Idempotency-Key wrapper; adds nothing. */
    @OwnerProtected(CLOSURE_PROTECTED)
    record ClosureGate<R>(Command<R> command) implements Command<R> {

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

    /** As {@link ClosureGate}, for Maintenance. */
    @OwnerProtected(MAINTENANCE_PROTECTED)
    record MaintenanceGate<R>(Command<R> command) implements Command<R> {

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

    /** As {@link ClosureGate}, for Disable and Archive. */
    @OwnerProtected(RESOURCE_PROTECTED)
    record ResourceGate<R>(Command<R> command) implements Command<R> {

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
     * Under the save's locks: the Emergency unresolved, the recomputed impact against the token, the Owner Password the
     * target needs; then the target activated and the Emergency CONVERTED with its block closed at the same instant -
     * one commit, no sellable gap (FR-OPS-24; {@code tr_emergency_conversion_target_exists} checks the target exists).
     */
    private static Emergency converted(CommandContext context, Convert convert, OwnerPasswordCheck check) {
        Deps deps = convert.deps();
        Emergency before = unresolved(context, convert.venueId(), convert.emergencyId());
        ConversionImpact impact = conversion(context, deps, before, convert.conversion());
        if (!impact.previewToken().equals(convert.previewToken())
                || !covered(convert.locks(), impact.affected())) {
            throw new ApiException(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW);
        }
        if (check == null && impact.ownerPasswordRequired()) {
            throw new ApiException(ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        }
        Instant now = context.commandNow();
        Conversion conversion = convert.conversion();
        UUID targetId;
        switch (conversion.target()) {
            case CLOSURE -> targetId = ClosureCommands.persist(context, deps.closures(), convert.venueId(),
                    convert.venueAccountId(), convert.correlationId(), Optional.empty(),
                    new ClosureCommands.Proposal(conversion.closureKind(), now, conversion.endAt(),
                            conversion.reason()), impact.closureImpact(), check).id();
            case MAINTENANCE -> {
                targetId = UUID.randomUUID();
                context.jdbc().sql("""
                                INSERT INTO operations.maintenance_window (id, resource_id, period, reason,
                                                                           created_from_emergency_id,
                                                                           owner_password_used)
                                VALUES (:id, :resource, tstzrange(CAST(:start AS timestamptz),
                                        CAST(:end AS timestamptz), '[)'), :reason, :emergency, :used)""")
                        .param("id", targetId).param("resource", before.resourceId())
                        .param("start", MaintenanceCommands.utc(now))
                        .param("end", MaintenanceCommands.utc(conversion.endAt()))
                        .param("reason", conversion.reason().strip()).param("emergency", before.id())
                        .param("used", impact.overThreshold()).update();
                deps.closures().ledger().create(context, new NewReservation(before.resourceId(),
                        ReservationOwner.maintenance(targetId), now, conversion.endAt()));
                AuditRecord.Builder audit = AuditRecord.builder(OperationsAuditAction.MAINTENANCE_CREATED,
                                AuditActor.venue(convert.venueAccountId()), targetId, convert.correlationId())
                        .contextVenue(convert.venueId())
                        .payload(Map.of("resourceId", before.resourceId(), "startAt", now.toString(),
                                "endAt", conversion.endAt().toString(), "ownerPasswordUsed", impact.overThreshold()));
                if (check != null) {
                    audit.ownerPasswordCheck(check);
                }
                deps.audit().write(context, audit.build());
                deps.outbox().publish(context, new NewOutboxEvent(IntegrationEvents.MAINTENANCE_CREATED,
                        "MAINTENANCE", targetId, Map.of("venueId", convert.venueId().toString(),
                        "resourceId", before.resourceId().toString(), "maintenanceId", targetId.toString())));
            }
            case DISABLED, ARCHIVED -> {
                targetId = before.resourceId();
                deps.baseStates().change(context, convert.venueAccountId(), convert.venueId(), before.resourceId(),
                        ResourceBaseState.valueOf(conversion.target().name()), check, convert.correlationId());
            }
            default -> throw new ApiException(ApiErrorCode.CONVERSION_TARGET_NOT_ALLOWED);
        }
        return convertedTo(context, deps, before, conversion.target(), targetId, convert.venueAccountId(),
                convert.correlationId(), check, convert.locks());
    }

    /**
     * The Emergency CONVERTED into {@code target} with its block closed at the database's resolution instant, audited
     * and published - shared by {@code /convert} and the opening-hours commit with {@code emergencyId} (D5). E7-83: the
     * timers it paused are decided again ({@link TimerPauses#afterResolution}); {@code locks} null (the hours commit,
     * whose locks are the hours change's) leaves each to a check job due now.
     */
    static Emergency convertedTo(CommandContext context, Deps deps, Emergency before, EmergencyConversionTarget target,
                                 UUID targetId, UUID venueAccountId, UUID correlationId, OwnerPasswordCheck check,
                                 LockSet locks) {
        Emergency emergency = resolve(context, deps, before, EmergencyStatus.CONVERTED, target);
        deps.timers().afterResolution(context, before.venueId(), before.resourceId(), locks);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("convertedTo", target.name());
        if (targetId != null) {
            extra.put("targetId", targetId.toString());
        }
        extra.put("endedAt", emergency.endedAt().toString());
        AuditRecord.Builder audit = AuditRecord.builder(OperationsAuditAction.EMERGENCY_CONVERTED,
                        AuditActor.venue(venueAccountId), before.id(), correlationId)
                .contextVenue(before.venueId())
                .stateChange(before.status().name(), EmergencyStatus.CONVERTED.name())
                .payload(subjectPayload(emergency, extra));
        if (check != null) {
            audit.ownerPasswordCheck(check);
        }
        deps.audit().write(context, audit.build());
        publish(context, deps, IntegrationEvents.EMERGENCY_CONVERTED, emergency);
        return emergency;
    }

    private static boolean covered(LockSet locks, Collection<Affected> affected) {
        return affected.stream().allMatch(row -> locks.resources().contains(row.resourceId())
                && (row.customerId() == null || locks.users().contains(row.customerId())));
    }

    // ---------------------------------------------------------------------------------------------- expected end

    /**
     * The expected end passed (FR-OPS-18): ACTIVE -> AWAITING_CONFIRMATION, the block kept - the expected end never
     * reopens anything; staff resolution is mandatory. Decided from the stored expected end, never from when the job
     * ran (arch 08 section 2.2): a resolved Emergency, or one extended since, supersedes the job.
     */
    enum ExpectedEndOutcome { DONE, SUPERSEDED, NOT_YET }

    /**
     * E7-84: the database's threshold trigger also runs at the expected end (FR-OPS-26: "at the emergency's expected or
     * actual end"); when it entered an Owner Review, its cascade runs here, inside {@code locks} - the locks the job
     * planned ({@link #ownerReviewLocks}); a reach beyond them is {@link StaleLocks}, and the job runs again.
     */
    static ExpectedEndOutcome expectedEnd(CommandContext context, Deps deps, UUID emergencyId, Instant dueAt,
                                          LockSet locks) {
        Optional<Emergency> found = context.jdbc().sql(SELECT + " WHERE e.id = :id").param("id", emergencyId)
                .query(Emergency::map).optional();
        if (found.isEmpty() || found.get().status() != EmergencyStatus.ACTIVE
                || !found.get().expectedEndAt().equals(dueAt)) {
            return ExpectedEndOutcome.SUPERSEDED;
        }
        Emergency emergency = found.get();
        if (context.commandNow().isBefore(emergency.expectedEndAt())) {
            return ExpectedEndOutcome.NOT_YET;
        }
        OwnerReviewCommands.Before reviewBefore = OwnerReviewCommands.beforeBoundary(context, deps, emergency);
        context.jdbc().sql("UPDATE operations.emergency SET status = 'AWAITING_CONFIRMATION' WHERE id = :id")
                .param("id", emergencyId).update();
        OwnerReviewCommands.afterBoundary(context, deps, read(context, emergency.venueId(), emergencyId).orElseThrow(),
                reviewBefore, locks);
        deps.audit().write(context, AuditRecord.builder(OperationsAuditAction.EMERGENCY_AWAITING_CONFIRMATION,
                        AuditActor.system(), emergencyId, CorrelationId.require())
                .contextVenue(emergency.venueId())
                .stateChange(EmergencyStatus.ACTIVE.name(), EmergencyStatus.AWAITING_CONFIRMATION.name())
                .payload(subjectPayload(emergency, Map.of("expectedEndAt", emergency.expectedEndAt().toString())))
                .build());
        return ExpectedEndOutcome.DONE;
    }

    /**
     * E7-84, read-only: what a resource Emergency's end or expected end needs locked in case the database enters an
     * Owner Review there - the Resource's live and expired bookings' Customers and its Tournaments (FR-OPS-28). Whether
     * it will enter one is the database's to say at the write (the threshold is never decided here), so a resource
     * Emergency's boundary always takes them.
     */
    static void ownerReviewLocks(CommandContext context, Deps deps, Emergency emergency, LockSet.Builder locks) {
        if (emergency.resourceId() != null) {
            OwnerReviewCommands.reach(context, deps, emergency.venueId(), emergency.resourceId()).addTo(locks);
        }
    }

    static String expectedEndKey(Emergency emergency) {
        return EXPECTED_END_JOB + ":" + emergency.id() + ":" + emergency.expectedEndAt().toEpochMilli();
    }

    private static void scheduleExpectedEnd(CommandContext context, Deps deps, Emergency emergency) {
        deps.jobs().schedule(context, new NewJob(EXPECTED_END_JOB, SUBJECT_TYPE, emergency.id(),
                emergency.expectedEndAt(), expectedEndKey(emergency)));
    }

    // ---------------------------------------------------------------------------------------------- the cascade

    /** What the cascade ended, for the audit row and the interruption records. */
    record Cascade(List<EmergencyBookings.Outcome> bookings, List<UUID> cancelledTournaments,
                   List<UUID> trimmedTournaments, int pausedTimers) {

        Map<String, Object> audit() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cancelledBookings", bookings.stream().filter(o -> o.attendance() == null).count());
            payload.put("interruptedBookings", bookings.stream().filter(o -> o.attendance() != null).count());
            payload.put("cancelledTournaments", cancelledTournaments.size());
            payload.put("pausedTimers", pausedTimers);
            return payload;
        }
    }

    /** Step 1, before the Emergency row: every live booking of the window ended (see the class comment). */
    private static Cascade cascadeBookings(CommandContext context, Deps deps, Subject subject, Reach reach,
                                           UUID venueAccountId) {
        List<EmergencyBookings.Outcome> outcomes = deps.bookings().end(context, subject.venueId(), venueAccountId,
                reach.ending().stream().map(Live::bookingId).toList(), reasonOf(subject.scope()));
        return new Cascade(outcomes, List.of(), List.of(), 0);
    }

    /**
     * Steps 2-4, after the Emergency row: the Tournaments, the paused timers, the interruption records. The outcomes of
     * step 1 are returned complete in {@code cascade}, so the caller's audit row counts everything.
     */
    private static Cascade cascadeRest(CommandContext context, Deps deps, Emergency emergency, Reach reach,
                                       Cascade cascade) {
        Instant now = context.commandNow();
        List<UUID> cancelled = new ArrayList<>();
        List<UUID> trimmed = new ArrayList<>();
        for (LiveTournament tournament : reach.tournaments()) {
            if (emergency.scope() == EmergencyScope.VENUE) {
                if (deps.tournaments().cancel(context, tournament.id(), TournamentTerminalReason.EMERGENCY_CLOSURE)) {
                    cancelled.add(tournament.id());
                }
            } else if (tournament.status() == TournamentStatus.ACTIVE) {
                // FR-TRN-27: an ACTIVE Tournament loses the resource and goes on while one operational resource remains
                deps.tournaments().removeResource(context, tournament.id(), emergency.resourceId(),
                        TournamentResourceRemovalCause.RESOURCE_EMERGENCY_OUTAGE);
                trimmed.add(tournament.id());
                boolean operational = tournament.resourceIds().stream()
                        .filter(id -> !id.equals(emergency.resourceId()))
                        .anyMatch(id -> operational(context, deps, id, now));
                if (!operational && deps.tournaments().cancel(context, tournament.id(),
                        TournamentTerminalReason.RESOURCE_EMERGENCY_OUTAGE)) {
                    cancelled.add(tournament.id());
                }
            } else if (deps.tournaments().cancel(context, tournament.id(),
                    TournamentTerminalReason.RESOURCE_EMERGENCY_OUTAGE)) {
                cancelled.add(tournament.id());
            }
        }

        // FR-OPS-34, FR-OPS-35: the pending requests outside the window wait, their timers paused; E7-83: each decided
        // at once - the feasibility test runs whenever a pause begins (FR-OPS-40) - and checked again until it resumes
        List<UUID> toPause = reach.outside().stream().filter(row -> !row.paused()).map(Live::bookingId).toList();
        List<Live> paused = deps.timers().pause(context, emergency.venueId(), emergency.bookingReason(), toPause);

        // Spec v5 section 53.2: one append-only operational interruption record per booking and Tournament it ended
        UUID correlationId = CorrelationId.require();
        for (EmergencyBookings.Outcome outcome : cascade.bookings()) {
            if (outcome.completed()) {
                continue;
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("emergencyId", emergency.id().toString());
            evidence.put("fromStatus", outcome.from().name());
            evidence.put("terminalReason", outcome.reason().name());
            evidence.put("startedAtStartAt", outcome.startedFirst());
            if (outcome.attendance() != null) {
                evidence.put("attendanceResult", outcome.attendance().name());
            }
            boolean online = outcome.source() == BookingSource.ONLINE;
            interruption(context, deps, online ? "BOOKING" : "MANUAL_BOOKING", outcome.bookingId(),
                    outcome.reason().name(), now, evidence, online, online ? outcome.bookingFee() : BigDecimal.ZERO,
                    correlationId);
        }
        for (UUID tournamentId : cancelled) {
            interruption(context, deps, "TOURNAMENT", tournamentId, (emergency.scope() == EmergencyScope.VENUE
                    ? TournamentTerminalReason.EMERGENCY_CLOSURE : TournamentTerminalReason.RESOURCE_EMERGENCY_OUTAGE).name(), now,
                    Map.of("emergencyId", emergency.id().toString()), false, BigDecimal.ZERO, correlationId);
        }
        return new Cascade(cascade.bookings(), List.copyOf(cancelled), List.copyOf(trimmed), paused.size());
    }

    static boolean operational(CommandContext context, Deps deps, UUID resourceId, Instant now) {
        return deps.resources().find(context, resourceId)
                .filter(room -> room.baseState() == ResourceBaseState.ACTIVE
                        && room.restriction() == OperationalRestriction.NONE)
                .isPresent() && !blocked(context, new Subject(deps.resources().find(context, resourceId)
                .orElseThrow().venueId(), EmergencyScope.RESOURCE, resourceId));
    }

    static void interruption(CommandContext context, Deps deps, String subjectType, UUID subjectId,
                                     String cause, Instant at, Map<String, Object> evidence, boolean refundApplied,
                                     BigDecimal refundAmount, UUID correlationId) {
        String json;
        try {
            json = deps.json().writeValueAsString(evidence);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        context.jdbc().sql("""
                        INSERT INTO operations.operational_interruption_record (id, subject_type, subject_id, cause,
                                                                               occurred_at, attendance_evidence,
                                                                               refund_applied, refund_amount,
                                                                               correlation_id)
                        VALUES (:id, :type, :subject, :cause, :at, CAST(:evidence AS jsonb), :applied, :amount,
                                :correlation)""")
                .param("id", UUID.randomUUID()).param("type", subjectType).param("subject", subjectId)
                .param("cause", cause).param("at", MaintenanceCommands.utc(at)).param("evidence", json)
                .param("applied", refundApplied).param("amount", refundAmount).param("correlation", correlationId)
                .update();
    }

    // ---------------------------------------------------------------------------------------------- shared steps

    /**
     * The Emergency ENDED or CONVERTED and its selling block closed at the same instant: the database stamps
     * {@code ended_at} ({@code guard_emergency_lifecycle}) and the block takes exactly that value
     * ({@code assert_emergency_block_consistency}). The expected-end job is withdrawn.
     */
    private static Emergency resolve(CommandContext context, Deps deps, Emergency before, EmergencyStatus status,
                                     EmergencyConversionTarget target) {
        context.jdbc().sql("UPDATE operations.emergency SET status = :status, converted_to = :target WHERE id = :id")
                .param("status", status.name()).param("target", target == null ? null : target.name())
                .param("id", before.id()).update();
        String table = before.scope() == EmergencyScope.VENUE ? "venue.venue_selling_block"
                : "resource.resource_selling_block";
        context.jdbc().sql("UPDATE " + table + """

                         SET active = false, ended_at = (SELECT e.ended_at FROM operations.emergency e WHERE e.id = :id)
                         WHERE emergency_id = :id AND active""")
                .param("id", before.id()).update();
        deps.jobs().supersede(context, expectedEndKey(before));
        return read(context, before.venueId(), before.id()).orElseThrow();
    }

    /** The venue's Emergency - else NOT_FOUND - still unresolved - else OPERATIONAL_STATE_CHANGED (D6). */
    static Emergency unresolved(CommandContext context, UUID venueId, UUID emergencyId) {
        Emergency emergency = read(context, venueId, emergencyId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (!emergency.unresolved()) {
            throw new ApiException(ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }
        return emergency;
    }

    static Optional<Emergency> read(CommandContext context, UUID venueId, UUID emergencyId) {
        return context.jdbc().sql(SELECT + " WHERE e.id = :id AND e.venue_id = :venue")
                .param("id", emergencyId).param("venue", venueId)
                .query(Emergency::map).optional();
    }

    /** An active selling block of the subject - an unresolved Emergency's, or any other (one per subject). */
    private static boolean blocked(CommandContext context, Subject subject) {
        String sql = subject.scope() == EmergencyScope.VENUE
                ? "SELECT EXISTS (SELECT 1 FROM venue.venue_selling_block WHERE venue_id = :id AND active)"
                : "SELECT EXISTS (SELECT 1 FROM resource.resource_selling_block WHERE resource_id = :id AND active)";
        return Boolean.TRUE.equals(context.jdbc().sql(sql)
                .param("id", subject.scope() == EmergencyScope.VENUE ? subject.venueId() : subject.resourceId())
                .query(Boolean.class).single());
    }

    /**
     * FR-OPS-07, FR-OPS-11: the expected end after {@code at} and no later than the end of the Operating Session in
     * force at {@code at} - none in force is out of session too (the database refuses both,
     * {@code guard_emergency_lifecycle}).
     */
    private static void requireInsideSession(CommandContext context, Deps deps, UUID venueId, Instant at,
                                             Instant expectedEnd) {
        if (expectedEnd == null || !expectedEnd.isAfter(context.commandNow()) || !expectedEnd.isAfter(at)) {
            throw new ApiException(ApiErrorCode.EMERGENCY_EXPECTED_END_OUT_OF_SESSION);
        }
        Optional<OperatingSessions.SessionPeriod> session = deps.sessions().endingAfter(context, venueId, at).stream()
                .filter(s -> !s.start().isAfter(at))
                .min(Comparator.comparing(OperatingSessions.SessionPeriod::start));
        if (session.isEmpty() || expectedEnd.isAfter(session.get().end())) {
            throw new ApiException(ApiErrorCode.EMERGENCY_EXPECTED_END_OUT_OF_SESSION);
        }
    }

    private static boolean closureOverlaps(CommandContext context, UUID venueId, Instant start, Instant end) {
        return Boolean.TRUE.equals(context.jdbc().sql("""
                        SELECT EXISTS (SELECT 1 FROM operations.venue_closure c
                                        WHERE c.venue_id = :venue AND c.deleted_at IS NULL
                                          AND c.period && tstzrange(CAST(:start AS timestamptz),
                                                                    CAST(:end AS timestamptz), '[)'))""")
                .param("venue", venueId).param("start", MaintenanceCommands.utc(start))
                .param("end", MaintenanceCommands.utc(end))
                .query(Boolean.class).single());
    }

    private static Map<String, Object> subjectPayload(Emergency emergency, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", emergency.scope().name());
        if (emergency.resourceId() != null) {
            payload.put("resourceId", emergency.resourceId().toString());
        }
        payload.putAll(extra);
        return payload;
    }

    /** {@code {venueId, emergencyId, scope, resourceId?}}: the Nearest Availability consumer refreshes the venue. */
    private static void publish(CommandContext context, Deps deps, String type, Emergency emergency) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("venueId", emergency.venueId().toString());
        payload.put("emergencyId", emergency.id().toString());
        payload.put("scope", emergency.scope().name());
        if (emergency.resourceId() != null) {
            payload.put("resourceId", emergency.resourceId().toString());
        }
        deps.outbox().publish(context, new NewOutboxEvent(type, SUBJECT_TYPE, emergency.id(), payload));
    }
}
