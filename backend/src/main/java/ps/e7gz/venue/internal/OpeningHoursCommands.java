package ps.e7gz.venue.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
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
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerPasswordNotRequired;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.inventory.ledger.api.ActiveCommitments.Commitment;
import ps.e7gz.venue.api.HoursChangeBookings;
import ps.e7gz.venue.api.HoursChangeBookings.BookingFacts;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.internal.OperatingSessionStore.Template;

/**
 * The opening-hours change (FR-VEN-23, arch 06 §2.4) and the nightly horizon extension (ADR-009), each one command
 * with the venue locked, so the affected set a commit acts on is the one it computed itself.
 */
final class OpeningHoursCommands {

    private OpeningHoursCommands() {
    }

    /** The Bookings an hours change can cancel (FR-VEN-24). ACTIVE has started; VENUE_RESPONSE_EXPIRED holds no row. */
    private static final Set<BookingStatus> CANCELLABLE = EnumSet.of(BookingStatus.PENDING,
            BookingStatus.AWAITING_CUSTOMER_CONFIRMATION, BookingStatus.CONFIRMED);

    private static final Set<ReservationType> TOURNAMENT_ROWS = EnumSet.of(ReservationType.TOURNAMENT_APPROVAL_HOLD,
            ReservationType.TOURNAMENT_HOLD);

    /** The Owner Password matrix id of a commit that cancels a protected booking (FRS §2.4, FR-VEN-25). */
    static final String PROTECTED_ACTION = "HOURS_CLOSURE_SAVE_CANCELLING_PROTECTED";

    /**
     * A future Booking the new hours would cancel. {@code customerId} is the Online Booking's Customer, whom the commit
     * locks; {@code null} for a Manual Booking. It is not part of the preview token: a booking never changes Customer.
     */
    record Affected(UUID bookingId, UUID resourceId, Instant startAt, Instant endAt, UUID customerId,
                    boolean protectedCancellation) {
    }

    /** The complete impact preview (FR-VEN-23 step 5), bound to its exact affected set by the token (arch 03 §5). */
    record Impact(String previewToken, List<Affected> affected) {

        /** Step 6 (FR-VEN-25): only a CONFIRMED Online or an AWAITING cancellation needs the Owner Password. */
        boolean ownerPasswordRequired() {
            return affected.stream().anyMatch(Affected::protectedCancellation);
        }

        /**
         * The rows a commit cancelling this set must lock before it starts (arch 04 §2.1, arch 05 §4.2): the venue,
         * every affected booking's Resource and every affected Online Booking's Customer - {@code begin_command} then
         * also locks those Customers' Bookings, Late Confirmations and reservations.
         */
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

    /** What the reads of a change need; the same for preview and commit. */
    record Readers(ActiveCommitments commitments, HoursChangeBookings bookings, int horizonDays) {
    }

    /** Steps 1–5, writing nothing: the tournament block, then the affected Bookings and the token. */
    record Preview(UUID venueId, OpeningHours hours, Readers readers) implements Command<Impact> {

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
            ZoneId zone = OperatingSessionStore.zoneOf(context.jdbc(), venueId);
            Template proposed = proposed(venueId, hours, OperatingSessionStore.template(context.jdbc(), venueId));
            return impact(context, proposed, zone, readers);
        }
    }

    /**
     * Steps 6–8 without the Owner Password (FRS §2.4 "Hours/Closure with no protected cancellation"): the hours, the
     * sessions and the cancellation of every affected PENDING Online or CONFIRMED Manual Booking, in one transaction.
     * When the set recomputed under the locks has a protected cancellation - a booking became CONFIRMED or AWAITING
     * since the preview - it answers {@code 423 OWNER_PASSWORD_REQUIRED} and writes nothing (FR-VEN-25).
     *
     * @param locks the lock set of the affected set the caller computed before the command ({@link Impact#lockSet});
     *              the command refuses a recomputed set it does not cover
     */
    @OwnerPasswordNotRequired("HOURS_CLOSURE_NO_PROTECTED_CANCELLATION")
    record Commit(UUID venueId, UUID venueAccountId, OpeningHours hours, String previewToken, LockSet locks,
                  Readers readers) implements Command<Template> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public Template execute(CommandContext context) {
            return committed(context, venueId, venueAccountId, hours, previewToken, locks, readers, false);
        }
    }

    /**
     * Steps 6–8 with the Owner Password (FRS §2.4 "Hours/Closure save cancelling protected bookings", FR-VEN-25), run
     * by the gate after it checked the password inside this transaction, before the Idempotency-Key lookup (arch 10 §3;
     * PO decision Q3, 24 Oct 2026); its success is audited on its own row (FR-VEN-05, arch 10 §8.1) - the hours change
     * itself has no audit row (PO decision 3 Oct 2026). Then as {@link Commit}, every affected booking included. Run
     * through {@link OwnerPasswordGate#run(Command, UUID, String, UUID)} (via {@link Protected}), which audits a refusal.
     */
    @OwnerProtected(PROTECTED_ACTION)
    record ProtectedCommit(UUID venueId, UUID venueAccountId, OpeningHours hours, String previewToken, LockSet locks,
                           UUID correlationId, Readers readers, OwnerPasswordGate gate,
                           AuditWriter audit) implements Command<Template> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public Template execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verified(context, venueAccountId);
            audit.write(context, AuditRecord.builder(VenueAccountAuditAction.OWNER_PASSWORD_ACCEPTED,
                            AuditActor.venue(venueAccountId), venueAccountId, correlationId)
                    .ownerPasswordCheck(check)
                    .payload(Map.of("protectedAction", PROTECTED_ACTION))
                    .build());
            return committed(context, venueId, venueAccountId, hours, previewToken, locks, readers, true);
        }

        /** The venue and the actor only, as before; the password is the gate's alone (NFR-21). */
        @Override
        public String toString() {
            return "ProtectedCommit[venueId=" + venueId + ", venueAccountId=" + venueAccountId + "]";
        }
    }

    /**
     * The command {@link OwnerPasswordGate#run} is given for a protected commit: the Idempotency-Key wrapper around a
     * {@link ProtectedCommit}. The gate checks the password before it, so before the Idempotency-Key lookup. It only
     * carries the action id the gate audits a refusal under, and adds nothing to the transaction.
     */
    @OwnerProtected(PROTECTED_ACTION)
    record Protected<R>(Command<R> command) implements Command<R> {

        Protected {
            Objects.requireNonNull(command, "command");
        }

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
     * Steps 6–8 under the command's locks: the account, the recomputed impact and its token (FR-VEN-26), the locks it
     * needs, the Owner Password it needs, then the cancellations (FR-VEN-24, FR-VEN-28: one Booking port call, one
     * Loyalty batch) and the hours with their sessions (ADR-009) - all in this one transaction or none of it.
     * Referenced sessions keep their row (Spec v5.0 §14.6), cancelled bookings included.
     */
    private static Template committed(CommandContext context, UUID venueId, UUID venueAccountId, OpeningHours hours,
                                      String previewToken, LockSet locks, Readers readers, boolean passwordChecked) {
        requireEnabledAccount(context, venueId, venueAccountId);
        ZoneId zone = OperatingSessionStore.zoneOf(context.jdbc(), venueId);
        Optional<Template> current = OperatingSessionStore.template(context.jdbc(), venueId);
        Template proposed = proposed(venueId, hours, current);
        Impact impact = impact(context, proposed, zone, readers);
        if (!impact.previewToken().equals(previewToken)) {
            throw new ApiException(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW);
        }
        // The token binds the bookings, not who must be locked: a set the caller's locks do not cover was computed
        // from a different set than this one - the venue is shown the refreshed preview (FR-VEN-26), nothing is written.
        if (!covers(locks, impact.affected())) {
            throw new ApiException(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW);
        }
        if (!passwordChecked && impact.ownerPasswordRequired()) {
            throw new ApiException(ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        }
        readers.bookings().cancel(context, venueId, venueAccountId,
                impact.affected().stream().map(Affected::bookingId).toList());
        Template saved = OperatingSessionStore.save(context, proposed, current.isEmpty());
        OperatingSessionStore.apply(context, saved, zone,
                OperatingSessionStore.plan(context, saved, zone, readers.horizonDays()));
        return saved;
    }

    /**
     * Category C (FRS §2.3): the acting account is the venue's own and ENABLED, read under the command's lock on the
     * venue and its account. The token filter refuses a disabled account first (401); this is the race of one disabled
     * between the request and the command - FORBIDDEN before any write, as for the venue's other commands.
     */
    private static void requireEnabledAccount(CommandContext context, UUID venueId, UUID venueAccountId) {
        String status = context.jdbc().sql("""
                        SELECT account_status FROM iam.venue_account WHERE id = :account AND venue_id = :venue""")
                .param("account", venueAccountId).param("venue", venueId)
                .query(String.class).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        if (!"ENABLED".equals(status)) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        }
    }

    /** Every affected booking's Resource and Online Customer is in {@code locks}. */
    private static boolean covers(LockSet locks, Collection<Affected> affected) {
        return affected.stream().allMatch(row -> locks.resources().contains(row.resourceId())
                && (row.customerId() == null || locks.users().contains(row.customerId())));
    }

    /** The nightly run: the same regeneration from the saved hours, which in practice adds the new last day. */
    record Extend(UUID venueId, int horizonDays) implements Command<Boolean> {

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
            Optional<Template> template = OperatingSessionStore.template(context.jdbc(), venueId);
            if (template.isEmpty()) {
                return false;
            }
            ZoneId zone = OperatingSessionStore.zoneOf(context.jdbc(), venueId);
            OperatingSessionStore.apply(context, template.get(), zone,
                    OperatingSessionStore.plan(context, template.get(), zone, horizonDays));
            return true;
        }
    }

    /** The hours as the template they would become: same row, next version. */
    private static Template proposed(UUID venueId, OpeningHours hours, Optional<Template> current) {
        return new Template(current.map(Template::id).orElseGet(UUID::randomUUID), venueId, hours,
                current.map(t -> t.version() + 1).orElse(1L));
    }

    /**
     * Steps 2–5. A future commitment row that no session after the change contains is uncovered (FR-BOK-05: a
     * Booking fits one session). An uncovered Tournament hold blocks the save (FR-VEN-27), before any Booking is
     * looked at; an uncovered Booking still to come is affected (FR-VEN-24). A Manual Booking made outside published
     * hours never was in a session (FR-MAN-03) and stays.
     */
    private static Impact impact(CommandContext context, Template proposed, ZoneId zone, Readers readers) {
        SessionPlan plan = OperatingSessionStore.plan(context, proposed, zone, readers.horizonDays());
        List<Commitment> uncovered = readers.commitments()
                .startingAfter(context, proposed.venueId(), context.commandNow()).stream()
                .filter(row -> plan.containers().stream()
                        .noneMatch(session -> session.contains(row.plannedStart(), row.plannedEnd())))
                .toList();
        if (uncovered.stream().anyMatch(row -> TOURNAMENT_ROWS.contains(row.owner().type()))) {
            throw new ApiException(ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE);
        }
        Map<UUID, BookingFacts> facts = readers.bookings().facts(context,
                uncovered.stream().map(row -> row.owner().id()).distinct().toList());
        List<Affected> affected = new ArrayList<>();
        for (Commitment row : uncovered) {
            BookingFacts booking = facts.get(row.owner().id());
            // FR-AVL-15: an AWAITING booking whose stored Late Confirm deadline is reached belongs to T2, not to O5
            if (booking != null && booking.insideOperatingSession() && CANCELLABLE.contains(booking.status())
                    && !booking.lateConfirmTimedOut()) {
                affected.add(new Affected(booking.bookingId(), row.resourceId(), row.plannedStart(),
                        row.plannedEnd(), booking.source() == BookingSource.ONLINE ? booking.customerId() : null,
                        booking.protectedCancellation()));
            }
        }
        affected.sort(Comparator.comparing(Affected::startAt).thenComparing(Affected::bookingId));
        return new Impact(token(proposed, affected), List.copyOf(affected));
    }

    /**
     * Binds a preview to the venue, the proposed hours and the exact affected set. Not a secret: the commit recomputes
     * the set under the lock, so the token only proves which set the venue was shown (FR-VEN-26).
     */
    static String token(Template proposed, List<Affected> affected) {
        StringBuilder canonical = new StringBuilder("v1|").append(proposed.venueId()).append('|')
                .append(proposed.hours().defaultOpen()).append('|').append(proposed.hours().defaultClose())
                .append('|').append(proposed.hours().defaultFullDay());
        proposed.hours().dayList().forEach(day -> canonical.append('|').append(day.weekday()).append(':')
                .append(day.mode()).append(':').append(day.customOpen()).append(':').append(day.customClose()));
        affected.forEach(row -> canonical.append("|b:").append(row.bookingId()).append(':').append(row.resourceId())
                .append(':').append(row.startAt()).append(':').append(row.endAt()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
