package ps.e7gz.seed;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;

/**
 * A batch of Customers as registration and phone verification leave them (FR-ACC-01, FR-ACC-02): the same
 * {@code iam.user_account} row {@code RegisterCustomerCommand} writes, with {@code phone_verified} set as
 * {@code VerifyPhoneCommand} sets it. No OTP challenge: the seed has no SMS. The database triggers add the loyalty
 * accounts and the commitment guard as for any new Customer. Joined dates are spread over the past year, counted back
 * from the command instant.
 */
record SeedCustomersCommand(List<Row> rows, String passwordHash, int firstIndex) implements Command<Void> {

    record Row(UUID id, String fullName, String username, String phoneE164, String email, boolean verified,
               int joinedDaysAgo) {
    }

    /** Referral codes use the registration alphabet; the seed numbers them so they never collide. */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;
    }

    /** The Users do not exist yet, so there is nothing to lock. */
    @Override
    public CommandScope scope() {
        return CommandScope.shared(LockSet.empty());
    }

    @Override
    public Void execute(CommandContext ctx) {
        OffsetDateTime now = OffsetDateTime.ofInstant(ctx.commandNow(), ZoneOffset.UTC);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            OffsetDateTime joined = now.minus(row.joinedDaysAgo(), ChronoUnit.DAYS);
            ctx.jdbc().sql("""
                            INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash,
                                                          phone_verified, phone_verified_at, email_verified,
                                                          referral_code, joined_at)
                            VALUES (:id, :fullName, :username, :phone, :email, :passwordHash,
                                    :verified, :verifiedAt, false, :referralCode, :joinedAt)""")
                    .param("id", row.id()).param("fullName", row.fullName()).param("username", row.username())
                    .param("phone", row.phoneE164()).param("email", row.email()).param("passwordHash", passwordHash)
                    .param("verified", row.verified())
                    .param("verifiedAt", row.verified() ? joined.plusMinutes(5) : null)
                    .param("referralCode", referralCode(firstIndex + i)).param("joinedAt", joined)
                    .update();
        }
        return null;
    }

    /** Eight characters, as registration makes them: {@code S}, then {@code n} in base 31 over the same alphabet. */
    static String referralCode(int n) {
        char[] code = new char[8];
        code[0] = 'S';
        int value = n;
        for (int i = code.length - 1; i > 0; i--) {
            code[i] = ALPHABET[value % ALPHABET.length];
            value /= ALPHABET.length;
        }
        return new String(code);
    }
}
