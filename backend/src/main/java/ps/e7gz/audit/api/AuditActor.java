package ps.e7gz.audit.api;

import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.ActorType;

/**
 * Who performed an audited action (FR-AUD-06). SYSTEM has no id; every other actor has one. Whether the id is a real
 * User / Venue Account / active Admin is checked by the database ({@code audit.guard_audit_insert}).
 */
public sealed interface AuditActor {

    ActorType actorType();

    /** {@code null} only for {@link ActorType#SYSTEM}. */
    UUID actorId();

    static AuditActor system() {
        return SystemActor.INSTANCE;
    }

    static AuditActor customer(UUID userId) {
        return new Customer(userId);
    }

    static AuditActor venue(UUID venueAccountId) {
        return new Venue(venueAccountId);
    }

    static AuditActor admin(UUID adminId) {
        return new Admin(adminId);
    }

    enum SystemActor implements AuditActor {
        INSTANCE;

        @Override
        public ActorType actorType() {
            return ActorType.SYSTEM;
        }

        @Override
        public UUID actorId() {
            return null;
        }
    }

    record Customer(UUID actorId) implements AuditActor {

        public Customer {
            Objects.requireNonNull(actorId, "a CUSTOMER actor needs the User id");
        }

        @Override
        public ActorType actorType() {
            return ActorType.CUSTOMER;
        }
    }

    record Venue(UUID actorId) implements AuditActor {

        public Venue {
            Objects.requireNonNull(actorId, "a VENUE actor needs the Venue Account id");
        }

        @Override
        public ActorType actorType() {
            return ActorType.VENUE;
        }
    }

    record Admin(UUID actorId) implements AuditActor {

        public Admin {
            Objects.requireNonNull(actorId, "an ADMIN actor needs the Admin id");
        }

        @Override
        public ActorType actorType() {
            return ActorType.ADMIN;
        }
    }
}
