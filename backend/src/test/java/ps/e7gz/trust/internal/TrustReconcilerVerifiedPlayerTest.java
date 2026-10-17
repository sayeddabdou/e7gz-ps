package ps.e7gz.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.trust.api.TrustReconciler.VerifiedPlayerChange;

/**
 * The automatic Verified Player rules the reconciler hands to E7-72 (arch 07 section 6.2), the same two refusals as
 * {@code trust.assert_user_integrity}: admin SUSPENDED and REVOKED are never changed automatically (FR-ACC-15).
 */
class TrustReconcilerVerifiedPlayerTest {

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "NOT_QUALIFIED, -,          -,                  -",
            "NOT_QUALIFIED, VERIFIED,   TRUST_QUALIFICATION, REVOKE_BY_CORRECTION",
            "NOT_QUALIFIED, REVOKED,    TRUST_CORRECTION,   -",
            "NOT_QUALIFIED, SUSPENDED,  ADMIN,              -",
            "QUALIFIED,     -,          -,                  VERIFY",
            "QUALIFIED,     NOT_VERIFIED, ADMIN,            VERIFY",
            "QUALIFIED,     REVOKED,    TRUST_CORRECTION,   VERIFY",
            "QUALIFIED,     REVOKED,    ADMIN,              -",
            "QUALIFIED,     SUSPENDED,  ADMIN,              -",
            "QUALIFIED,     VERIFIED,   TRUST_QUALIFICATION, -"})
    void followsTheReplayedQualificationExceptAdminProtection(VerifiedPlayerQualification qualification,
                                                              String status, String cause,
                                                              VerifiedPlayerChange expected) {
        assertThat(JdbcTrustReconciler.verifiedPlayerChange(qualification, status, cause)).isEqualTo(expected);
    }
}
