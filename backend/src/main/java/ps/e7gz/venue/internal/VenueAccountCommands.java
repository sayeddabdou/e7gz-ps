package ps.e7gz.venue.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.NotificationRecipientType;
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
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.Notifications;
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
                    AuditActor.admin(adminId), id, correlationId)
                    .contextVenue(venueId).payload(Map.of("venueId", venueId)).build());
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
                    .ownerPasswordCheck(check).contextVenue(venueId).payload(Map.of("venueId", venueId))
                    .build());
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

    /**
     * The Platform Admin disables or re-enables Dashboard access (FR-ADM-03, FR-VEN-08), with a reason; marketplace
     * status and venue data are untouched (FR-VEN-06). Disabled, the account cannot log in and its tokens stop working
     * on the next request (the per-request active check, arch 10 §2.3). Setting the status it already has changes
     * nothing and writes no audit row.
     *
     * <p>Only the access part of O10 exists yet: interrupting PENDING bookings, cancelling AWAITING ones and the
     * restriction interval for attendance protection come with E7-78. The database already refuses a disable while the
     * venue has a tournament waiting for its approval ({@code tr_tournament_account_disable_from_account}).
     */
    record SetAccountStatus(UUID adminId, UUID correlationId, UUID venueAccountId, UUID venueId,
                            VenueAccountStatus target, String reason, AuditWriter audit, Outbox outbox)
            implements Command<Venues.VenueAccountSummary> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().venues(venueId).build());
        }

        @Override
        public Venues.VenueAccountSummary execute(CommandContext context) {
            Venues.VenueAccountSummary current = context.jdbc().sql("""
                            SELECT id, venue_id, login_identifier, account_status FROM iam.venue_account
                             WHERE id = :account FOR UPDATE""")
                    .param("account", venueAccountId).query(VenueAccountCommands::summary).optional()
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            if (current.status() == target) {
                return current;
            }
            context.jdbc().sql("UPDATE iam.venue_account SET account_status = :status WHERE id = :account")
                    .param("status", target.name()).param("account", venueAccountId).update();
            if (target == VenueAccountStatus.DISABLED) {
                // arch 08 section 5, FR-VEN-08: open Dashboard streams close at once (E7-44, PO decision 10 Oct 2026).
                outbox.publish(context, new NewOutboxEvent(IntegrationEvents.VENUE_ACCOUNT_DISABLED, "VENUE_ACCOUNT",
                        venueAccountId, Map.of("venueAccountId", venueAccountId, "venueId", venueId)));
            }
            VenueAccountAuditAction action = target == VenueAccountStatus.DISABLED
                    ? VenueAccountAuditAction.VENUE_ACCOUNT_DISABLED : VenueAccountAuditAction.VENUE_ACCOUNT_ENABLED;
            audit.write(context, AuditRecord.builder(action, AuditActor.admin(adminId), venueAccountId, correlationId)
                    .reason(reason)
                    .stateChange(current.status().name(), target.name())
                    .contextVenue(venueId)
                    .payload(Map.of("venueId", venueId, "previousStatus", current.status().name(),
                            "newStatus", target.name()))
                    .build());
            return new Venues.VenueAccountSummary(current.id(), current.venueId(), current.loginIdentifier(), target);
        }
    }

    /**
     * The Platform Admin sets a new venue login password (FR-ADM-03; PO decision 28 Sep 2026: the admin chooses it and
     * gives it to the venue through support). No temporary password, reset link or forced change; venue data is kept.
     */
    record ResetLoginPassword(UUID adminId, UUID correlationId, UUID venueAccountId, UUID venueId, String passwordHash,
                              AuditWriter audit) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().venues(venueId).build());
        }

        @Override
        public Void execute(CommandContext context) {
            int changed = context.jdbc().sql("UPDATE iam.venue_account SET password_hash = :hash WHERE id = :account")
                    .param("hash", passwordHash).param("account", venueAccountId).update();
            if (changed != 1) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            audit.write(context, AuditRecord.builder(VenueAccountAuditAction.VENUE_LOGIN_PASSWORD_RESET,
                    AuditActor.admin(adminId), venueAccountId, correlationId)
                    .contextVenue(venueId).payload(Map.of("venueId", venueId)).build());
            return null;
        }

        @Override
        public String toString() {
            return "ResetLoginPassword[venueAccountId=" + venueAccountId + ", <password redacted>]";
        }
    }

    /**
     * The Platform Admin sets a new Owner Password (FR-ADM-03): like the venue's own change, the new hash gets a later
     * {@code changed_at} and the next {@code version}, so the old one stops working at once. The venue is notified in
     * the same transaction (FR-VEN-05, FR-ADM-03; E7-43): {@code VENUE_OWNER_PASSWORD_RESET}, never with the password.
     */
    record ResetOwnerPassword(UUID adminId, UUID correlationId, UUID venueAccountId, UUID venueId,
                              String ownerPasswordHash, AuditWriter audit, Notifications notifications)
            implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.builder().venues(venueId).build());
        }

        @Override
        public Void execute(CommandContext context) {
            int changed = context.jdbc().sql("""
                            UPDATE iam.venue_owner_secret
                               SET owner_password_hash = :hash, changed_at = :changedAt, version = version + 1
                             WHERE venue_account_id = :account""")
                    .param("hash", ownerPasswordHash).param("changedAt", utc(context)).param("account", venueAccountId)
                    .update();
            if (changed != 1) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            audit.write(context, AuditRecord.builder(VenueAccountAuditAction.OWNER_PASSWORD_RESET,
                    AuditActor.admin(adminId), venueAccountId, correlationId)
                    .contextVenue(venueId).payload(Map.of("venueId", venueId)).build());
            notifications.notify(context, NotificationRequest.of("VENUE_OWNER_PASSWORD_RESET",
                    NotificationRecipientType.VENUE, venueId, Map.of()));
            return null;
        }

        @Override
        public String toString() {
            return "ResetOwnerPassword[venueAccountId=" + venueAccountId + ", <password redacted>]";
        }
    }

    static Venues.VenueAccountSummary summary(ResultSet rs, int row) throws SQLException {
        return new Venues.VenueAccountSummary(rs.getObject("id", UUID.class), rs.getObject("venue_id", UUID.class),
                rs.getString("login_identifier"), VenueAccountStatus.valueOf(rs.getString("account_status")));
    }
}
