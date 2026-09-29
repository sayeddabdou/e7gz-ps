package ps.e7gz.identity.api;

import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The marketplace activation gate (FR-ACC-02, FR-ACC-03, Spec v5 section 7.2 and section 7.4): only a phone-verified
 * account may create a Booking, join or create a Tournament, or write a Review. Email verification never activates.
 *
 * <p>Call it inside the command, after {@code begin_command} has locked the User, so the answer cannot change before
 * the command commits. Verification state is read from the database on every call, never from a token (arch 10
 * section 2.3).
 */
public interface CustomerActivation {

    /**
     * @throws ps.e7gz.common.error.ApiException {@code ACCOUNT_DELETED} (403) for an anonymised account (FR-ACC-10),
     *         {@code PHONE_VERIFICATION_REQUIRED} (403) when the phone is not verified (FR-ACC-03)
     * @throws IllegalStateException when the User does not exist (a caller defect: the lock set would have failed)
     */
    void requirePhoneVerified(CommandContext command, UUID userId);
}
