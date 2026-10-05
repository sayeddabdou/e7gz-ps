package ps.e7gz.admin.internal;

import java.util.UUID;
import org.springframework.context.ApplicationContext;

/**
 * E7-28: the seed's way to the first-admin bootstrap, which is package-private on purpose (only the
 * {@code admin-bootstrap} profile runs it). Lives in the seed source set, so it is never in the application jar. The
 * admin is created exactly as the bootstrap creates one: validated, Argon2id-hashed, audited, refused once any admin
 * exists.
 */
public final class SeedAdmins {

    private SeedAdmins() {
    }

    /** The {@code password} array is cleared before this returns. */
    public static UUID createFirst(ApplicationContext context, String email, String fullName, char[] password) {
        return context.getBean(FirstAdminBootstrap.class).create(email, fullName, password);
    }
}
