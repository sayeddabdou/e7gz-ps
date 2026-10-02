package ps.e7gz.admin.internal;

import java.util.Arrays;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.identity.api.PasswordRules;

/**
 * Creates the first Platform Admin (Sprint 0 review S-5, Product Owner decision 2 Oct 2026, E7-23): a one-off, audited
 * operation with no default credentials anywhere in code, configuration or migrations, and no admin MFA at launch.
 * Started by {@link FirstAdminBootstrapRunner}; refused once any admin exists.
 *
 * <p>The password is hashed with the application's Argon2id encoder (arch 10 section 2.2) and the plain value is wiped
 * from memory after hashing. It is never logged.
 */
@Component
class FirstAdminBootstrap {

    static final int MAX_EMAIL_LENGTH = 254;

    private final CommandRunner runner;
    private final AuditWriter audit;
    private final PasswordEncoder passwordEncoder;

    FirstAdminBootstrap(CommandRunner runner, AuditWriter audit, PasswordEncoder passwordEncoder) {
        this.runner = runner;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
    }

    /** Creates the admin and returns its id; the {@code password} array is cleared before this returns. */
    UUID create(String email, String fullName, char[] password) {
        try {
            String cleanEmail = email == null ? "" : email.strip();
            String cleanName = fullName == null ? "" : fullName.strip();
            if (cleanEmail.isEmpty() || cleanEmail.length() > MAX_EMAIL_LENGTH || cleanEmail.indexOf('@') < 1
                    || cleanEmail.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("A valid admin e-mail is required (e7gz.admin-bootstrap.email)");
            }
            if (cleanName.isEmpty()) {
                throw new IllegalArgumentException("The admin's full name is required (e7gz.admin-bootstrap.full-name)");
            }
            // Same rule as Customer passwords (Product Owner decision 2 Oct 2026, E7-23).
            if (password == null || !PasswordRules.isAcceptable(String.valueOf(password))) {
                throw new IllegalArgumentException(
                        "The admin password needs at least 8 characters, with at least one letter and one digit");
            }
            String hash = passwordEncoder.encode(String.valueOf(password));
            UUID correlationId = CorrelationId.generate();
            try (CorrelationId.Scope ignored = CorrelationId.open(correlationId)) {
                return runner.run(new CreateFirstAdminCommand(cleanEmail, cleanName, hash, correlationId, audit));
            }
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }
}
