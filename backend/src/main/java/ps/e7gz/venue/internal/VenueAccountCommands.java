package ps.e7gz.venue.internal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerPasswordNotRequired;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * Writes of the venue's shared account ({@code iam.venue_account}) and its Owner Password
 * ({@code iam.venue_owner_secret}), each locking the venue. Passwords arrive here already hashed (Argon2id); no command
 * holds a raw password except the current Owner Password it checks, which it never writes, logs or returns.
 */
final class VenueAccountCommands {

    private VenueAccountCommands() {
    }

    static OffsetDateTime utc(CommandContext context) {
        return OffsetDateTime.ofInstant(context.commandNow(), ZoneOffset.UTC);
    }

    /**
     * The Platform Admin creates the venue's one account, ENABLED, with its first Owner Password (FR-ADM-02,
     * FR-VEN-01; E7-22 contract). A second account for the venue is {@code uq_venue_account_venue} and a login already
     * used is {@code ux_venue_login_normalized}; both are mapped by {@code DatabaseErrorCodes}.
     */
    record Create(UUID adminId, UUID correlationId, UUID venueId, String loginIdentifier, String passwordHash,
                  String ownerPasswordHash, VenueAccountLanguage language, AuditWriter audit)
            implements Command<Venues.VenueAccountSummary> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;      // onboarding, not marketplace (ADR-022)
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().venues(venueId).build());
        }

        @Override
        public Venues.VenueAccountSummary execute(CommandContext context) {
            UUID id = UUID.randomUUID();
            context.jdbc().sql("""
                            INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                           language_preference)
                            VALUES (:id, :venue, :login, :hash, :status, :language)""")
                    .param("id", id).param("venue", venueId).param("login", loginIdentifier)
                    .param("hash", passwordHash).param("status", VenueAccountStatus.ENABLED.name())
                    .param("language", language.name()).update();
            context.jdbc().sql("""
                            INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at, version)
                            VALUES (:account, :hash, :changedAt, 0)""")
                    .param("account", id).param("hash", ownerPasswordHash).param("changedAt", utc(context)).update();
            audit.write(context, AuditRecord.builder(VenueAccountAuditAction.VENUE_ACCOUNT_CREATED,
                    AuditActor.admin(adminId), id, correlationId).payload(Map.of("venueId", venueId)).build());
            return new Venues.VenueAccountSummary(id, venueId, loginIdentifier, VenueAccountStatus.ENABLED);
        }
    }

    /**
     * The venue changes its own Owner Password (FR-VEN-46): the current one is checked first, inside this transaction;
     * the new hash replaces it with a later {@code changed_at} and the next {@code version}
     * ({@code tr_127_venue_owner_secret_guard}), so the old one stops working the moment this commits.
     */
    @OwnerProtected("OWNER_PASSWORD_CHANGE")
    record ChangeOwnerPassword(UUID venueAccountId, UUID venueId, UUID correlationId, String currentPassword,
                               String nextPasswordHash, OwnerPasswordGate gate, AuditWriter audit)
            implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Void execute(CommandContext context) {
            OwnerPasswordCheck check = gate.verify(context, venueAccountId, currentPassword);
            int changed = context.jdbc().sql("""
                            UPDATE iam.venue_owner_secret
                               SET owner_password_hash = :hash, changed_at = :changedAt, version = version + 1
                             WHERE venue_account_id = :account""")
                    .param("hash", nextPasswordHash).param("changedAt", utc(context)).param("account", venueAccountId)
                    .update();
            if (changed != 1) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            audit.write(context, AuditRecord.builder(VenueAccountAuditAction.OWNER_PASSWORD_CHANGED,
                            AuditActor.venue(venueAccountId), venueAccountId, correlationId)
                    .ownerPasswordCheck(check).payload(Map.of("venueId", venueId)).build());
            return null;
        }

        @Override
        public String toString() {
            return "ChangeOwnerPassword[venueAccountId=" + venueAccountId + ", <passwords redacted>]";
        }
    }

    /**
     * The venue edits its name, address, phone and Dashboard language (PO decision 3 Oct 2026, arch 09 §4.2,
     * FR-VEN-40); a null field is left unchanged. Not in the FRS §2.4 protected list, so no Owner Password. Not audited,
     * like the Platform Admin's venue edit (PO decision 2 Oct 2026, E7-23).
     */
    @OwnerPasswordNotRequired("VENUE_SETTINGS_UPDATE")
    record UpdateSettings(UUID venueAccountId, UUID venueId, String name, String address, String phone,
                          VenueAccountLanguage language) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public Void execute(CommandContext context) {
            if (name != null || address != null || phone != null) {
                context.jdbc().sql("""
                                UPDATE venue.venue
                                   SET name = COALESCE(:name, name),
                                       address = COALESCE(:address, address),
                                       phone = COALESCE(:phone, phone)
                                 WHERE id = :venue""")
                        .param("name", name).param("address", address).param("phone", phone).param("venue", venueId)
                        .update();
            }
            if (language != null) {
                context.jdbc().sql("UPDATE iam.venue_account SET language_preference = :language WHERE id = :account")
                        .param("language", language.name()).param("account", venueAccountId).update();
            }
            return null;
        }
    }
}
