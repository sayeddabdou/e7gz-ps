package ps.e7gz.loyalty.api;

import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * A referral is settled by the Account OTP verification of the friend it brought (Product v5.0 §46.4, FR-PTS-06,
 * FR-PTS-07; E7-67): in the same transaction, {@code loyalty.reward_referral} grants the referrer the referral points
 * in force at that verification instant and stores the value, or makes the referral INVALID when the two accounts now
 * share a phone or an email (FR-PTS-08). The database refuses to commit a verification that leaves the referral PENDING
 * ({@code loyalty.assert_referral}: "OTP success must reconcile referral reward/invalidity before commit").
 *
 * <p>Declared here and called by identity, which verifies the phone; identity already depends on loyalty's api
 * ({@link AccountHolders}), never the other way round (CLAUDE.md section 3).
 */
public interface ReferralSettlement {

    /**
     * The referrer of {@code referredUserId}'s PENDING referral, read before the verifying command so that command can
     * lock both Users; empty when there is no PENDING referral.
     */
    Optional<UUID> pendingReferrer(UUID referredUserId);

    /**
     * Inside the command that verifies the phone, with both Users in its lock set: opens the Loyalty command context
     * for them and lets {@code loyalty.reward_referral} reward or invalidate the PENDING referral. Nothing to do once
     * it is no longer PENDING. Call it once, as that command's only Loyalty entry point.
     */
    void settle(CommandContext command, UUID referredUserId, UUID referrerId);
}
