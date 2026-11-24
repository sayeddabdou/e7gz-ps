package ps.e7gz.tournament.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * What a new Tournament asks about each Resource it would hold (FR-TRN-33, FR-TRN-08; E7-91): the answer of the
 * canonical availability predicate (arch 05 section 2.1), in the Tournament's terms. A port the tournament module
 * declares and {@code inventory.availability} implements (CLAUDE.md section 3): availability already depends on
 * operations, which depends on this module's cascades, so this module may not depend on availability.
 */
public interface TournamentResourceAvailability {

    /**
     * Why {@code resourceId} cannot be held for {@code [startAt, endAt)} at {@code at}, the command instant, or empty
     * when it is free. Runs on the command's transaction, under its locks.
     */
    Optional<Blocker> blocker(CommandContext command, UUID resourceId, Instant startAt, Instant endAt, Instant at);

    /** The eight checks of the predicate, grouped as a new Tournament answers them (arch 09 section 1.5). */
    enum Blocker {

        /** Check 1: the Venue's operational selling block. */
        VENUE_SELLING_BLOCK,

        /** Check 2: the Resource's operational selling block. */
        RESOURCE_SELLING_BLOCK,

        /** Checks 3-4: the Resource is not ACTIVE, or is under owner review. */
        NOT_ELIGIBLE,

        /**
         * Checks 5-8: something already holds the interval - an emergency's declared interval, Maintenance, a Closure,
         * or a booking or hold. Existing reservations always win (FR-TRN-08).
         */
        ALREADY_RESERVED
    }
}
