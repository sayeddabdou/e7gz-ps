package ps.e7gz.identity.api;

import ps.e7gz.identity.internal.PasswordPolicy;

/**
 * The password rule for other modules: at least 8 characters with a letter and a digit (VALIDATION RULES section 2.1).
 * The Platform Admin password uses the same rule (Product Owner decision 2 Oct 2026, E7-23).
 */
public final class PasswordRules {

    private PasswordRules() {
    }

    public static boolean isAcceptable(String password) {
        return PasswordPolicy.isAcceptable(password);
    }
}
