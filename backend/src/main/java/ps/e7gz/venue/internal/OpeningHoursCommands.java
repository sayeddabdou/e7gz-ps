package ps.e7gz.venue.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.inventory.ledger.api.ActiveCommitments.Commitment;
import ps.e7gz.venue.api.HoursChangeBookings;
import ps.e7gz.venue.api.HoursChangeBookings.BookingFacts;
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

    /** A future Booking the new hours would cancel. */
    record Affected(UUID bookingId, UUID resourceId, Instant startAt, Instant endAt, boolean protectedCancellation) {
    }

    /** The complete impact preview (FR-VEN-23 step 5), bound to its exact affected set by the token (arch 03 §5). */
    record Impact(String previewToken, List<Affected> affected) {

        /** Step 6 (FR-VEN-25): only a CONFIRMED Online or an AWAITING cancellation needs the Owner Password. */
        boolean ownerPasswordRequired() {
            return affected.stream().anyMatch(Affected::protectedCancellation);
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
     * Steps 6–8: recompute the impact under the lock; a different set than the preview showed aborts with a refreshed
     * preview (FR-VEN-26); then save the hours and regenerate the sessions in the same transaction (ADR-009).
     *
     * <p>Cancelling affected Bookings (step 8's cancellations, step 9's notifications, FR-VEN-28) needs the Booking,
     * Late Confirm and Messaging modules, which do not exist yet (PO decision 3 Oct 2026, E7-25): until they do, a
     * change that would cancel anything answers 501 and changes nothing. So every change that commits cancels nothing
     * and needs no Owner Password (FRS §2.4, "Hours/Closure with no protected cancellation").
     */
    @OwnerPasswordNotRequired("HOURS_CLOSURE_NO_PROTECTED_CANCELLATION")
    record Commit(UUID venueId, OpeningHours hours, String previewToken, Readers readers)
            implements Command<Template> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Template execute(CommandContext context) {
            ZoneId zone = OperatingSessionStore.zoneOf(context.jdbc(), venueId);
            Optional<Template> current = OperatingSessionStore.template(context.jdbc(), venueId);
            Template proposed = proposed(venueId, hours, current);
            Impact impact = impact(context, proposed, zone, readers);
            if (!impact.previewToken().equals(previewToken)) {
                throw new ApiException(ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW);
            }
            if (!impact.affected().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
            }
            Template saved = OperatingSessionStore.save(context, proposed, current.isEmpty());
            OperatingSessionStore.apply(context, saved, zone,
                    OperatingSessionStore.plan(context, saved, zone, readers.horizonDays()));
            return saved;
        }
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
            if (booking != null && booking.insideOperatingSession() && CANCELLABLE.contains(booking.status())) {
                affected.add(new Affected(booking.bookingId(), row.resourceId(), row.plannedStart(),
                        row.plannedEnd(), booking.protectedCancellation()));
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
