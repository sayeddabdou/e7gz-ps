package ps.e7gz.booking.internal;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.operations.api.OperationalStateAt;
import ps.e7gz.resource.api.ResourceStates;

/**
 * O11's decision - what a CONFIRMED booking's start resolves to (Spec v5 sections 31.2-31.6, FR-BOK-25..28, arch 05
 * section 7) - judged from the authoritative state <b>as of the stored {@code startAt}</b>, never as of when it runs
 * (arch 08 section 2.2), so a late caller gets the on-time outcome. Read-only: the caller makes the transition, under
 * the booking's Customer, Venue and Resource locks.
 *
 * <p>Built for the delayed-start no-show (decisions of Amr Heiba, acting decision-maker, 17 Oct 2026: a customer is never
 * penalised where the start should have cancelled) as the smallest boundary E7-60's BOOKING_START handler can reuse;
 * E7-60 still owns the transition itself, atomic with this revalidation, and its own notifications.
 *
 * <p>First the start-time causes, each read explicitly from its own state (E7-47 D3), in the frozen priority of
 * section 31.6 - exactly one reason:
 * <ol>
 *   <li>Owner Review in force at startAt - {@code RESOURCE_OWNER_REVIEW_REQUIRED};</li>
 *   <li>a Resource Emergency unresolved at startAt - {@code RESOURCE_EMERGENCY_OUTAGE};</li>
 *   <li>a Venue Emergency unresolved at startAt - {@code VENUE_EMERGENCY_CLOSURE};</li>
 *   <li>a Venue Closure overlapping the booking - {@code VENUE_CLOSURE};</li>
 *   <li>{@code VENUE_ACCOUNT_DISABLED} never applies to a CONFIRMED booking (section 31.5);</li>
 *   <li>Resource Base State not ACTIVE at startAt - {@code SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR}, with its SEV_1
 *       alert (section 31.4).</li>
 * </ol>
 * Then the gates of section 31.2 through the one canonical predicate (arch 01 section 15 rule 1), on the booked
 * interval with the selling blocks read at startAt. The booking's own reservation always fails check 8, so check 8
 * failing means every gate passed: the booking starts. Any earlier check failing is {@link Outcome.Undetermined} - never
 * guessed into a reason, never started: a selling block here is an orphaned block, corrupted state
 * ({@link Outcome.Undetermined#isOrphanedBlock}, E7-60), and with no cause at startAt any other failing check is a
 * state that arose since (a later emergency, owner review or base-state change), which the start-time table does not
 * decide.
 */
@Component
class StartTimeResolution {

    private final ResourceStates resources;
    private final OperationalStateAt operations;
    private final AvailabilityQuery availability;

    StartTimeResolution(ResourceStates resources, OperationalStateAt operations, AvailabilityQuery availability) {
        this.resources = resources;
        this.operations = operations;
        this.availability = availability;
    }

    /** What the booking's start resolves to, judged at its {@code startAt}. */
    Outcome resolve(CommandContext context, Facts booking) {
        Objects.requireNonNull(booking, "booking");
        Instant at = booking.startAt();
        if (operations.ownerReviewAt(context, booking.resourceId(), at)) {
            return new Outcome.Cancel(BookingTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED, null);
        }
        if (operations.resourceEmergencyAt(context, booking.resourceId(), at)) {
            return new Outcome.Cancel(BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE, null);
        }
        if (operations.venueEmergencyAt(context, booking.venueId(), at)) {
            return new Outcome.Cancel(BookingTerminalReason.VENUE_EMERGENCY_CLOSURE, null);
        }
        if (operations.closurePreventsService(context, booking.venueId(), booking.startAt(), booking.endAt())) {
            return new Outcome.Cancel(BookingTerminalReason.VENUE_CLOSURE, null);
        }
        ResourceBaseState base = resources.baseStateAt(context, booking.resourceId(), at)
                .orElseThrow(() -> new IllegalStateException("Resource " + booking.resourceId() + " vanished"));
        if (base != ResourceBaseState.ACTIVE) {
            return new Outcome.Cancel(BookingTerminalReason.SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, base.name());
        }
        Optional<AvailabilityCheck> failing = availability.isFree(context, booking.resourceId(),
                new AvailabilityPeriod(booking.startAt(), booking.endAt()), at).failingCheck();
        if (failing.isPresent() && failing.get() != AvailabilityCheck.BLOCKING_RESERVATION) {
            return new Outcome.Undetermined("start gate " + failing.get() + " failing without a start-time cause",
                    failing.get());
        }
        // an empty answer means the booking's own reservation is missing: the caller's ledger read finds that defect
        return new Outcome.Start();
    }

    /** The start-time resolution. */
    sealed interface Outcome {

        /** Every gate passed at {@code startAt}: the booking starts (CONFIRMED -> ACTIVE). */
        record Start() implements Outcome {
        }

        /**
         * A cause held at {@code startAt}: CONFIRMED -> CANCELLED under {@code reason}, with no customer penalty and the
         * full refund classification of the reason map (section 31.3).
         *
         * @param unexpectedBaseState the base state found, for {@code SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR}'s alert
         */
        record Cancel(BookingTerminalReason reason, String unexpectedBaseState) implements Outcome {

            public Cancel {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /**
         * The state does not determine one start-time outcome; the caller changes nothing.
         *
         * @param failingCheck the predicate's first failing check (E7-60: a selling block here, read at startAt after
         *                     both emergency causes were ruled out, is an orphaned block - see {@link #isOrphanedBlock})
         */
        record Undetermined(String why, AvailabilityCheck failingCheck) implements Outcome {

            public Undetermined {
                Objects.requireNonNull(failingCheck, "failingCheck");
            }

            /**
             * A Venue or Resource Operational Selling Block in force at startAt with no Emergency unresolved at startAt:
             * corrupted state, never a Product state. A block exists only for an unresolved Emergency (Spec v5 sections
             * 38.2 and 42.1, FR-OPS-15, FR-OPS-19), and the database keeps an Emergency's block in lockstep with it
             * ({@code guard_emergency_selling_block} sets its start to {@code declared_at};
             * {@code assert_emergency_block_consistency} closes it at the Emergency's {@code ended_at}), read here over
             * the same half-open window as the Emergency checks above. So only a block with no Emergency behind it
             * ({@code emergency_id} NULL), which no Product flow writes, can fail here.
             */
            boolean isOrphanedBlock() {
                return failingCheck == AvailabilityCheck.VENUE_SELLING_BLOCK
                        || failingCheck == AvailabilityCheck.RESOURCE_SELLING_BLOCK;
            }
        }
    }
}
