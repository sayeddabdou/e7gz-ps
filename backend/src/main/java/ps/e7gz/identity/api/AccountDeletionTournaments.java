package ps.e7gz.identity.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The Tournament side of an account deletion / anonymisation, O19 (E7-94; arch 06 section 3.7, arch 07 section 5.7,
 * ADR-023, FR-TRN-26, PRD section 7.1): in the command that anonymised the User ({@code iam.anonymise_user}), after it,
 * every participation of the User in a non-terminal Tournament becomes {@code INELIGIBLE} with cause
 * {@code ACCOUNT_DELETED}:
 * <ul>
 *   <li>{@code JOINED -> INELIGIBLE/ACCOUNT_DELETED} - out of the Active Participant count;</li>
 *   <li>{@code INELIGIBLE/BAN -> INELIGIBLE/ACCOUNT_DELETED} - the cause alone changes, and the BAN-only reserved slot
 *       is released at once. ACCOUNT_DELETED beats BAN and never goes back: no later Ban change restores the row or
 *       turns it into BAN again.</li>
 * </ul>
 * The database refuses the commit otherwise ({@code tournament.assert_deleted_user_participant_cascade}).
 *
 * <p>Declared here and implemented by the Tournament module, which depends on Identity (CLAUDE.md section 3). The
 * anonymisation command (Sprint 7) calls it; that command must name the User in its lock set, so that
 * {@code begin_command} locks every live Tournament the User is in. Running it twice changes nothing the second time.
 */
public interface AccountDeletionTournaments {

    void accountDeleted(CommandContext command, UUID userId);
}
