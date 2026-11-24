package ps.e7gz.venue.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * What a Venue Account disable needs from the Tournaments of its Venue (arch 06 section 2.8, FR-TRN-18, FR-TRN-19;
 * E7-78): every Tournament still PENDING_VENUE_APPROVAL is CANCELLED {@code VENUE_ACCOUNT_DISABLED} at once - the
 * database refuses the disable's commit otherwise ({@code tr_tournament_account_disable_from_account}). Later states
 * are not cancelled by an access disable alone. Declared here and implemented by the Tournament module, so Venue never
 * depends on Tournament (CLAUDE.md section 3).
 */
public interface AccountDisableTournaments {

    /** The Venue's PENDING_VENUE_APPROVAL Tournaments, with the Resources they hold, in start order. Read-only. */
    List<AwaitingApproval> awaitingApproval(CommandContext command, UUID venueId);

    /**
     * Cancels exactly {@code tournamentIds} under {@code VENUE_ACCOUNT_DISABLED} as a system consequence (actor SYSTEM,
     * no cooldown and no venue non-response blame - the database decides both from the reason), releasing their holds.
     * The disable's restriction interval must already be written in this command (FR-TRN-31: the reason must be the
     * highest cause in force), and every id must be in the command's {@code LockSet.tournaments()}.
     */
    void cancel(CommandContext command, List<UUID> tournamentIds);

    /** One Tournament awaiting the venue's approval and the Resources its holds are on. */
    record AwaitingApproval(UUID tournamentId, List<UUID> resourceIds) {

        public AwaitingApproval {
            Objects.requireNonNull(tournamentId, "tournamentId");
            resourceIds = List.copyOf(resourceIds);
        }
    }
}
