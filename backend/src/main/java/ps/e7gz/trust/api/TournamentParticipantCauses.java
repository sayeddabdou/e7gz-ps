package ps.e7gz.trust.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The participant cause reconciler of ADR-023 for O14 (arch 06 section 3.7, FR-TRN-13, FR-TRN-26; E7-93): after a
 * command changed a User's Bans - an activation, an extension, an expiry, a lift, a correction or a shortening - every
 * Tournament participation of the User is brought in line with the Ban now in force, in the same transaction:
 * <ul>
 *   <li>a Ban in force: {@code JOINED -> INELIGIBLE} with cause {@code BAN} in every non-terminal Tournament (removed from
 *       the Active Participant count at once; before {@code startAt} the row keeps one capacity slot reserved);</li>
 *   <li>no Ban in force: {@code INELIGIBLE/BAN -> JOINED} while the Tournament is non-terminal, the account active and
 *       {@code now < startAt} - the row takes back its reserved slot, never a newly free one. At or after
 *       {@code startAt} there is no restoration, and a terminal Tournament is never touched.</li>
 * </ul>
 * A Tournament whose checkpoint or start is already due is resolved first, as of its boundary (ADR-023 item 5): the
 * participant set may not change across a due boundary ({@code tournament.guard_tournament_participant}).
 *
 * <p>Declared here and implemented by the Tournament module, which depends on Trust (CLAUDE.md section 3). Trust calls it
 * at the end of every reconciliation ({@link TrustReconciler}), so each Ban change reaches it. The command must name
 * the User in its lock set: {@code begin_command} then locks every live Tournament the User is in, with its people,
 * Venue and Resources - the same serialization path as a Join. Running it twice changes nothing the second time. The
 * database proves the result at commit ({@code tournament.assert_ban_participant_cascade},
 * {@code tournament.assert_ban_participant_restore}).
 */
public interface TournamentParticipantCauses {

    void reconcileBans(CommandContext command, UUID userId);
}
