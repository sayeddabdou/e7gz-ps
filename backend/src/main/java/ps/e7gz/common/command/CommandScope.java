package ps.e7gz.common.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Which database command context a command opens (arch 04 section 2.1, ADR-026 section 3).
 */
public sealed interface CommandScope {

    LockSet lockSet();

    /** {@code tournament.begin_command(...)} on the app-runtime connection. */
    static CommandScope shared(LockSet lockSet) {
        return new Shared(lockSet);
    }

    /** {@code admin.begin_command(admin, correlation, users[], venues[])} on the admin-runtime connection. */
    static CommandScope admin(UUID adminId, UUID correlationId, LockSet lockSet) {
        return new Admin(adminId, correlationId, lockSet);
    }

    record Shared(LockSet lockSet) implements CommandScope {

        public Shared {
            Objects.requireNonNull(lockSet, "lockSet");
        }
    }

    /** {@code admin.begin_command} names only Users and Venues. */
    record Admin(UUID adminId, UUID correlationId, LockSet lockSet) implements CommandScope {

        public Admin {
            Objects.requireNonNull(adminId, "adminId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(lockSet, "lockSet");
            if (!lockSet.resources().isEmpty() || !lockSet.tournaments().isEmpty()
                    || !lockSet.newTournaments().isEmpty()) {
                throw new IllegalArgumentException("admin.begin_command locks only Users and Venues");
            }
        }
    }
}
