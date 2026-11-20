package ps.e7gz.operations.internal;

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
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.common.codes.VenueClosureKind;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.command.PreviewTokens;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerPasswordNotRequired;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.inventory.ledger.api.ActiveCommitments.Commitment;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.HoursChangeBookings;
import ps.e7gz.venue.api.HoursChangeBookings.BookingFacts;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * The normal Venue Closure (FR-VEN-29…FR-VEN-33, Product Spec §15, FRS F-17; E7-81) as the opening-hours change does it
 * (PO decision 19 Nov 2026, E7-77 D2): a preview lists the bookings the Closure would cancel, bound to that set by a
 * token ({@link PreviewTokens}); the save recomputes the set under the venue, room and customer locks and refuses one
 * that changed (409 AFFECTED_SET_CHANGED_REFRESH_PREVIEW, FR-VEN-26). In one transaction the save then cancels every
 * affected booking CANCELLED / VENUE_CLOSURE through the very cancellation the hours change uses
 * ({@link HoursChangeBookings#cancel(CommandContext, UUID, UUID, List, BookingTerminalReason)}, E7-113: reservations
 * released, Late Confirm closed, Booking Fee decisions in one Loyalty batch, {@code BookingCancelled} for the
 * notification), writes the {@code operations.venue_closure} row and then one {@code CLOSURE} row per room (arch 05 §3.2:
 * both records; the database's {@code tr_venue_closure_affected_set_resolved} refuses a Closure that leaves a booking or
 * Tournament unresolved in its interval). So no Closure is left overlapping a CONFIRMED booking (PO decision 19 Oct
 * 2026), and E7-60's start check remains the backstop.
 */
final class ClosureCommands {

    private ClosureCommands() {
    }

    /** Bookings a Closure cancels; an ACTIVE one refuses it (FR-VEN-31). VENUE_RESPONSE_EXPIRED holds no row. */
    private static final Set<BookingStatus> CANCELLABLE = EnumSet.of(BookingStatus.PENDING,
            BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, BookingStatus.CONFIRMED);

    private static final Set<ReservationType> TOURNAMENT_ROWS = EnumSet.of(ReservationType.TOURNAMENT_APPROVAL_HOLD,
            ReservationType.TOURNAMENT_HOLD);

    /** The Owner Password matrix ids the hours change already uses (FRS §2.4 "Hours/Closure save ..."). */
    static final String PROTECTED_ACTION = "HOURS_CLOSURE_SAVE_CANCELLING_PROTECTED";
    static final String UNPROTECTED_ACTION = "HOURS_CLOSURE_NO_PROTECTED_CANCELLATION";

    /** A proposed Closure (Spec v5 §15: full-day or partial, the whole venue). */
    record Proposal(VenueClosureKind kind, Instant startAt, Instant endAt, String reason) {

        Proposal {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
            reason = reason == null ? null : reason.strip();
        }
    }

    /** One stored Closure. */
    record Closure(UUID id, UUID venueId, VenueClosureKind kind, Instant startAt, Instant endAt, String reason) {

        static Closure map(ResultSet rs, int row) throws SQLException {
            return new Closure(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                    VenueClosureKind.valueOf(rs.getString("kind")),
                    rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("end_at", OffsetDateTime.class).toInstant(), rs.getString("reason"));
        }
    }

    static final String SELECT = """
            SELECT c.id, c.venue_id, c.kind, lower(c.period) AS start_at, upper(c.period) AS end_at, c.reason
              FROM operations.venue_closure c
            """;

    /** A booking the Closure would cancel; {@code customerId} is the Online Booking's, whom the save locks. */
    record Affected(UUID bookingId, UUID resourceId, Instant startAt, Instant endAt, UUID customerId,
                    boolean protectedCancellation) {
    }

    /**
     * The impact and its token. {@code awaitingTimeout}: an AWAITING booking in the interval whose Late Confirm deadline
     * has passed - T2 owns its outcome (FR-AVL-15), so it is not in the set, and a save waits for T2 (409).
     */
    record Impact(String previewToken, List<Affected> affected, boolean awaitingTimeout) {

        boolean ownerPasswordRequired() {
            return affected.stream().anyMatch(Affected::protectedCancellation);
        }

        /** The venue, every affected room and every affected Online customer (arch 04 §2.1). */
        LockSet lockSet(UUID venueId) {
            LockSet.Builder locks = LockSet.builder().venues(venueId);
            affected.forEach(row -> {
                locks.resources(row.resourceId());
                if (row.customerId() != null) {
                    locks.users(row.customerId());
                }
            });
            return locks.build();
        }
    }

    /** What the commands need besides their own fields. */
    record Deps(ActiveCommitments commitments, HoursChangeBookings bookings, ReservationLedger ledger,
                ResourceCatalog resources, AuditWriter audit, Outbox outbox, OwnerPasswordGate gate) {
    }

    // ---------------------------------------------------------------------------------------------- the commands

    /** The preview: writes nothing. With {@code closureId}, of an edit of that Closure. */
    record Preview(UUID venueId, UUID closureId, Proposal proposal, Deps deps) implements Command<Impact> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Impact execute(CommandContext context) {
            if (closureId != null) {
                live(context, venueId, closureId);
            }
            return impact(context, venueId, closureId, proposal, deps);
        }
    }

    /** The save without the Owner Password (FRS §2.4 "Hours/Closure with no protected cancellation"). */
    @OwnerPasswordNotRequired(UNPROTECTED_ACTION)
    record Save(UUID venueId, UUID venueAccountId, UUID correlationId, UUID closureId, Proposal proposal,
                String previewToken, LockSet locks, Deps deps) implements Command<Closure> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public Closure execute(CommandContext context) {
            return saved(context, this, null);
        }
    }

    /**
     * The save with the Owner Password (FR-VEN-30: "Save & Cancel Affected Bookings"), run by the gate after it checked
     * the password in this transaction, before the Idempotency-Key lookup (PO decision Q3, 24 Oct 2026, as the hours
     * commit). The Closure's own audit row records the check.
     */
    @OwnerProtected(PROTECTED_ACTION)
    record ProtectedSave(Save save) implements Command<Closure> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return save.scope();
        }

        @Override
        public Closure execute(CommandContext context) {
            OwnerPasswordCheck check = save.deps().gate().verified(context, save.venueAccountId());
            return saved(context, save, check);
        }
    }

    /** What the gate is given: the Idempotency-Key wrapper around a {@link ProtectedSave}; adds nothing itself. */
    @OwnerProtected(PROTECTED_ACTION)
    record Protected<R>(Command<R> command) implements Command<R> {

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
     * Delete a Closure that has not ended: no Owner Password; its CLOSURE rows are released; bookings it cancelled stay
     * cancelled (the E7-77 contract).
     */
    record Delete(UUID venueId, UUID venueAccountId, UUID correlationId, UUID closureId, Deps deps)
            implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Void execute(CommandContext context) {
            Closure closure = live(context, venueId, closureId);
            Instant now = context.commandNow();
            deps.ledger().releaseAll(context, ReservationOwner.closure(closureId), now, "CLOSURE_DELETED");
            context.jdbc().sql("UPDATE operations.venue_closure SET deleted_at = :now WHERE id = :id")
                    .param("now", MaintenanceCommands.utc(now)).param("id", closureId).update();
            deps.audit().write(context, AuditRecord.builder(OperationsAuditAction.CLOSURE_DELETED,
                            AuditActor.venue(venueAccountId), closureId, correlationId)
                    .contextVenue(venueId)
                    .payload(Map.of("kind", closure.kind().name(), "startAt", closure.startAt().toString(),
                            "endAt", closure.endAt().toString()))
                    .build());
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------- shared steps

    /**
     * Under the save's locks: the Closure being edited, the recomputed impact against the token, the locks and the
     * Owner Password it needs (FR-VEN-30); then the cancellations, the Closure row and its CLOSURE rows - all of it or
     * nothing.
     */
    private static Closure saved(CommandContext context, Save save, OwnerPasswordCheck check) {
        Deps deps = save.deps();
        UUID venueId = save.venueId();
        Optional<Closure> before = save.closureId() == null ? Optional.empty()
                : Optional.of(live(context, venueId, save.closureId()));
        Impact impact = impact(context, venueId, save.closureId(), save.proposal(), deps);
        if (!impact.previewToken().equals(save.previewToken()) || impact.awaitingTimeout()
                || !covers(save.locks(), impact.affected())) {
            throw new ApiException(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW);
        }
        if (check == null && impact.ownerPasswordRequired()) {
            throw new ApiException(ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        }
        deps.bookings().cancel(context, venueId, save.venueAccountId(),
                impact.affected().stream().map(Affected::bookingId).toList(), BookingTerminalReason.VENUE_CLOSURE);

        Proposal proposal = save.proposal();
        Instant now = context.commandNow();
        UUID id;
        boolean periodChanged;
        if (before.isEmpty()) {
            id = UUID.randomUUID();
            periodChanged = true;
            context.jdbc().sql("""
                            INSERT INTO operations.venue_closure (id, venue_id, period, kind, reason)
                            VALUES (:id, :venue, tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz),
                                    '[)'), :kind, :reason)""")
                    .param("id", id).param("venue", venueId).param("start", MaintenanceCommands.utc(proposal.startAt()))
                    .param("end", MaintenanceCommands.utc(proposal.endAt())).param("kind", proposal.kind().name())
                    .param("reason", proposal.reason()).update();
        } else {
            id = save.closureId();
            periodChanged = !before.get().startAt().equals(proposal.startAt())
                    || !before.get().endAt().equals(proposal.endAt());
            context.jdbc().sql("""
                            UPDATE operations.venue_closure
                               SET period = tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                   kind = :kind, reason = :reason
                             WHERE id = :id""")
                    .param("id", id).param("start", MaintenanceCommands.utc(proposal.startAt()))
                    .param("end", MaintenanceCommands.utc(proposal.endAt())).param("kind", proposal.kind().name())
                    .param("reason", proposal.reason()).update();
            if (periodChanged) {
                // Guard rows are immutable (arch 05 §3.2): the old ones go, the new interval's come, in this transaction
                deps.ledger().releaseAll(context, ReservationOwner.closure(id), now, "CLOSURE_EDITED");
            }
        }
        if (periodChanged) {
            for (ResourceCatalog.Entry room : deps.resources().ofVenue(context, venueId)) {
                deps.ledger().create(context, new NewReservation(room.resourceId(), ReservationOwner.closure(id),
                        proposal.startAt(), proposal.endAt()));
            }
        }
        Closure closure = read(context, venueId, id).orElseThrow();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", closure.kind().name());
        payload.put("startAt", closure.startAt().toString());
        payload.put("endAt", closure.endAt().toString());
        before.ifPresent(old -> {
            payload.put("previousStartAt", old.startAt().toString());
            payload.put("previousEndAt", old.endAt().toString());
        });
        payload.put("cancelledBookings", impact.affected().size());
        AuditRecord.Builder audit = AuditRecord.builder(before.isEmpty() ? OperationsAuditAction.CLOSURE_CREATED
                                : OperationsAuditAction.CLOSURE_EDITED, AuditActor.venue(save.venueAccountId()), id,
                        save.correlationId())
                .contextVenue(venueId)
                .payload(payload);
        if (check != null) {
            audit.ownerPasswordCheck(check);
        }
        deps.audit().write(context, audit.build());
        if (before.isEmpty()) {
            // Sprint 2 review (PO decision 12 Oct 2026): the Nearest Availability consumer refreshes the venue at once
            deps.outbox().publish(context, new NewOutboxEvent(IntegrationEvents.CLOSURE_CREATED, "VENUE_CLOSURE", id,
                    Map.of("venueId", venueId.toString(), "closureId", id.toString())));
        }
        return closure;
    }

    /**
     * The checks in the order the rules give them, then the affected set (FR-VEN-29…FR-VEN-32): the interval; another
     * Closure of the venue overlapping it (the CLOSURE rows may never overlap, I1 - the Dashboard edits that one
     * instead); a Tournament in the interval blocks the save (FR-VEN-32) before any booking is looked at, as for hours
     * (FR-VEN-27); a booking already started - ACTIVE, or due to start and not yet started - refuses it (FR-VEN-31: a
     * Closure must begin after the sessions in progress end); every PENDING, AWAITING or CONFIRMED booking still to come
     * is affected, Manual ones included, whatever the hours (FR-VEN-29).
     */
    static Impact impact(CommandContext context, UUID venueId, UUID closureId, Proposal proposal, Deps deps) {
        if (proposal.reason() == null || proposal.reason().isEmpty()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "proposal.reason");
        }
        Instant now = context.commandNow();
        if (!proposal.endAt().isAfter(proposal.startAt()) || !proposal.endAt().isAfter(now)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "proposal.endAt");
        }
        boolean otherClosure = context.jdbc().sql("""
                        SELECT EXISTS (SELECT 1 FROM operations.venue_closure c
                                        WHERE c.venue_id = :venue AND c.deleted_at IS NULL
                                          AND (CAST(:closure AS uuid) IS NULL OR c.id <> CAST(:closure AS uuid))
                                          AND c.period && tstzrange(CAST(:start AS timestamptz),
                                                                    CAST(:end AS timestamptz), '[)'))""")
                .param("venue", venueId).param("closure", closureId)
                .param("start", MaintenanceCommands.utc(proposal.startAt()))
                .param("end", MaintenanceCommands.utc(proposal.endAt()))
                .query(Boolean.class).single();
        if (otherClosure) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "proposal.startAt");
        }
        List<Commitment> rows = deps.commitments().overlapping(context, venueId, proposal.startAt(),
                proposal.endAt());
        if (rows.stream().anyMatch(row -> TOURNAMENT_ROWS.contains(row.owner().type()))) {
            throw new ApiException(ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE);
        }
        Map<UUID, BookingFacts> facts = deps.bookings().facts(context,
                rows.stream().map(row -> row.owner().id()).distinct().toList());
        List<Affected> affected = new ArrayList<>();
        boolean awaitingTimeout = false;
        for (Commitment row : rows) {
            BookingFacts booking = facts.get(row.owner().id());
            if (booking == null) {
                continue;
            }
            if (booking.status() == BookingStatus.ACTIVE
                    || (CANCELLABLE.contains(booking.status()) && !row.plannedStart().isAfter(now))) {
                throw new ApiException(ApiErrorCode.CLOSURE_OVERLAPS_ACTIVE_SESSION);
            }
            if (booking.lateConfirmTimedOut()) {
                awaitingTimeout = true; // FR-AVL-15: T2 decides it first
                continue;
            }
            if (CANCELLABLE.contains(booking.status())) {
                affected.add(new Affected(booking.bookingId(), row.resourceId(), row.plannedStart(),
                        row.plannedEnd(), booking.source() == BookingSource.ONLINE ? booking.customerId() : null,
                        booking.protectedCancellation()));
            }
        }
        affected.sort(Comparator.comparing(Affected::startAt).thenComparing(Affected::bookingId));
        return new Impact(token(venueId, closureId, proposal, affected), List.copyOf(affected), awaitingTimeout);
    }

    /** Binds the preview to the venue, the Closure edited, the proposal and the exact affected set (FR-VEN-26). */
    static String token(UUID venueId, UUID closureId, Proposal proposal, List<Affected> affected) {
        StringBuilder canonical = new StringBuilder("closure|").append(venueId).append('|').append(closureId)
                .append('|').append(proposal.kind()).append('|').append(proposal.startAt()).append('|')
                .append(proposal.endAt()).append('|').append(proposal.reason());
        affected.forEach(row -> canonical.append("|b:").append(row.bookingId()).append(':').append(row.resourceId())
                .append(':').append(row.startAt()).append(':').append(row.endAt()));
        return PreviewTokens.of(canonical.toString());
    }

    private static boolean covers(LockSet locks, Collection<Affected> affected) {
        return affected.stream().allMatch(row -> locks.resources().contains(row.resourceId())
                && (row.customerId() == null || locks.users().contains(row.customerId())));
    }

    /**
     * The venue's Closure that still blocks (not deleted) - else NOT_FOUND - and has not ended at the command instant -
     * else OPERATIONAL_STATE_CHANGED (PO decision 19 Nov 2026, E7-77 D6).
     */
    static Closure live(CommandContext context, UUID venueId, UUID closureId) {
        Closure closure = read(context, venueId, closureId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (!closure.endAt().isAfter(context.commandNow())) {
            throw new ApiException(ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        }
        return closure;
    }

    private static Optional<Closure> read(CommandContext context, UUID venueId, UUID closureId) {
        return context.jdbc().sql(SELECT + " WHERE c.id = :id AND c.venue_id = :venue AND c.deleted_at IS NULL")
                .param("id", closureId).param("venue", venueId)
                .query(Closure::map).optional();
    }
}
