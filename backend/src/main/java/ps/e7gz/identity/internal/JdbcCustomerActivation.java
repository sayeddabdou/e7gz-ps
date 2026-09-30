package ps.e7gz.identity.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.identity.api.CustomerActivation;

@Component
class JdbcCustomerActivation implements CustomerActivation {

    private record State(boolean phoneVerified, boolean anonymised) {
    }

    @Override
    public void requirePhoneVerified(CommandContext command, UUID userId) {
        State state = command.jdbc().sql("""
                        SELECT phone_verified, anonymised_at IS NOT NULL AS anonymised
                          FROM iam.user_account WHERE id = :id
                        """)
                .param("id", userId)
                .query((rs, n) -> new State(rs.getBoolean("phone_verified"), rs.getBoolean("anonymised")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Unknown User " + userId));
        // Deleted first: FR-ACC-10 applies to any command against an anonymised account (physical model change report, "Branch splits").
        if (state.anonymised()) {
            throw new ApiException(ApiErrorCode.ACCOUNT_DELETED);
        }
        if (!state.phoneVerified()) {
            throw new ApiException(ApiErrorCode.PHONE_VERIFICATION_REQUIRED);
        }
    }
}
