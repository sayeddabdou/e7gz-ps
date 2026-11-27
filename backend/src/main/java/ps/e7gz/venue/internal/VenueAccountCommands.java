package ps.e7gz.venue.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.codes.BookingStatus;
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
import ps.e7gz.identity.api.OwnerPasswordAttempts;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.Notifications;
import ps.e7gz.notification.api.VenueBrowserPush;
import ps.e7gz.venue.api.AccountDisableBookings;
import ps.e7gz.venue.api.AccountDisableTournaments;
import ps.e7gz.venue.api.AccountRestrictions;
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

    /** What a disable writes besides the account row (O10; E7-78): the three ports and the shared writers. */
    record DisableDeps(AccountDisableBookings bookings, AccountDisableTournaments tournaments,
                       AccountRestrictions restrictions, AuditWriter audit, Outbox outbox,
                       VenueBrowserPush browserPush, AdminAlerts alerts) {
    }

    /** The disable reaches a booking or Tournament its lock set does not hold: plan again (E7-82's pattern). */
    static final class StaleLocks extends RuntimeException {

        StaleLocks() {
            super("The Venue Account disable reaches rows outside its lock set", null, false, false);
        }
    }

    /**
     * Read-only: the lock set a status change needs (arch 04 section 2.1). A disable of an ENABLED account writes the
     * Venue, every PENDING and AWAITING Online Booking's Resource and Customer and every Tournament awaiting approval
     * with its Resources; anything else needs the Venue alone.
     */
    record PlanStatus(UUID venueAccountId, UUID venueId, VenueAccountStatus target, DisableDeps deps)
            implements Command<LockSet> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public LockSet execute(CommandContext context) {
            LockSet.Builder locks = LockSet.builder().venues(venueId);
            Optional<VenueAccountStatus> status = context.jdbc().sql(
                            "SELECT account_status FROM iam.venue_account WHERE id = :account")
                    .param("account", venueAccountId).query(String.class).optional().map(VenueAccountStatus::valueOf);
            if (target == VenueAccountStatus.DISABLED && status.orElse(null) == VenueAccountStatus.ENABLED) {
                reach(context, deps, venueId, locks);
            }
            return locks.build();
        }
    }

    /** Adds to {@code locks} every row a disable of the Venue writes besides the Venue itself. */
    private static void reach(CommandContext context, DisableDeps deps, UUID venueId, LockSet.Builder locks) {
        deps.bookings().affected(context, venueId).stream().filter(AccountDisableBookings.Affected::interrupted)
                .forEach(booking -> locks.resources(booking.resourceId()).users(booking.customerId()));
        deps.tournaments().awaitingApproval(context, venueId).forEach(tournament -> {
            locks.tournaments(tournament.tournamentId());
            locks.resources(tournament.resourceIds().toArray(UUID[]::new));
        });
    }

    /**
     * The Platform Admin disables or re-enables Dashboard access (FR-ADM-03, FR-VEN-08), with a reason; marketplace
     * status and venue data are untouched (FR-VEN-06). Disabled, the account cannot log in and its tokens stop working
     * on the next request (the per-request active check, arch 10 §2.3). Setting the status it already has changes
     * nothing and writes no audit row.
     *
     * <p>A disable is O10 (arch 06 section 2.8, FR-VEN-09; E7-78), all in this one transaction under {@code locks}
     * ({@link PlanStatus}): the restriction interval opens at the command instant (FR-ATT-21); every Tournament awaiting
     * approval is CANCELLED {@code VENUE_ACCOUNT_DISABLED} - the database refuses the commit otherwise
     * ({@code tr_tournament_account_disable_from_account}); PENDING bookings go VENUE_RESPONSE_EXPIRED and AWAITING ones
     * CANCELLED {@code VENUE_ACCOUNT_DISABLED} with their refund; CONFIRMED ones are untouched (FR-VEN-10); ACTIVE ones
     * continue (FR-ATT-14) and each becomes a protection candidate with the customer's and the Platform Admin's
     * notifications (FR-ATT-20, FR-ATT-25, FR-ATT-26). Run once per Idempotency-Key, so a replay cancels and refunds
     * nothing twice (PO decision D4, 19 Nov 2026). A re-enable closes the open interval; nothing it ended comes back.
     */
    record SetAccountStatus(UUID adminId, UUID correlationId, UUID venueAccountId, UUID venueId,
                            VenueAccountStatus target, String reason, LockSet locks, DisableDeps deps)
            implements Command<Venues.VenueAccountSummary> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            // the cascade writes Resources and Tournaments, which admin.begin_command alone cannot lock
            return CommandScope.adminCascade(adminId, correlationId, locks);
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
                disable(context);
            } else {
                deps.restrictions().close(context, venueId);                                  // FR-ATT-21, E3
            }
            VenueAccountAuditAction action = target == VenueAccountStatus.DISABLED
                    ? VenueAccountAuditAction.VENUE_ACCOUNT_DISABLED : VenueAccountAuditAction.VENUE_ACCOUNT_ENABLED;
            deps.audit().write(context, AuditRecord.builder(action, AuditActor.admin(adminId), venueAccountId,
                            correlationId)
                    .reason(reason)
                    .stateChange(current.status().name(), target.name())
                    .contextVenue(venueId)
                    .payload(Map.of("venueId", venueId, "previousStatus", current.status().name(),
                            "newStatus", target.name()))
                    .build());
            return new Venues.VenueAccountSummary(current.id(), current.venueId(), current.loginIdentifier(), target);
        }

        private void disable(CommandContext context) {
            List<AccountDisableBookings.Affected> bookings = deps.bookings().affected(context, venueId);
            List<AccountDisableTournaments.AwaitingApproval> tournaments =
                    deps.tournaments().awaitingApproval(context, venueId);
            boolean covered = bookings.stream().filter(AccountDisableBookings.Affected::interrupted)
                    .allMatch(b -> locks.resources().contains(b.resourceId()) && locks.users().contains(b.customerId()))
                    && tournaments.stream().allMatch(t -> locks.tournaments().contains(t.tournamentId()));
            if (!covered) {
                throw new StaleLocks();
            }
            // The cause first: the Tournament reason must be the highest one in force (FR-TRN-31), and T6 reads it
            UUID interval = deps.restrictions().open(context, venueId, adminId, reason, correlationId);
            deps.tournaments().cancel(context, tournaments.stream()
                    .map(AccountDisableTournaments.AwaitingApproval::tournamentId).toList());
            deps.bookings().interrupt(context, venueId, bookings.stream()
                    .filter(AccountDisableBookings.Affected::interrupted)
                    .map(AccountDisableBookings.Affected::bookingId).toList());
            deps.restrictions().activeSessionsAffected(context, venueId, interval, bookings.stream()
                    .filter(b -> b.status() == BookingStatus.ACTIVE)
                    .map(b -> new AccountRestrictions.ActiveSession(b.bookingId(), b.customerId(), b.resourceId(),
                            b.startAt(), b.endAt()))
                    .toList());
            // arch 08 section 5, FR-VEN-08: open Dashboard streams close at once (E7-44, PO decision 10 Oct 2026).
            deps.outbox().publish(context, new NewOutboxEvent(IntegrationEvents.VENUE_ACCOUNT_DISABLED,
                    "VENUE_ACCOUNT", venueAccountId, Map.of("venueAccountId", venueAccountId, "venueId", venueId)));
            // ... and its browsers get no more Web Push (PO decision 10 Oct 2026, E7-44 review).
            deps.browserPush().revokeAll(context, venueAccountId);
            // E7-80 (Spec v5 section 52.6 "Venue Account Disabled", Q1): the admin alert, with the disable
            deps.alerts().raise(context, NewAdminAlert.venueAccountDisabled(venueAccountId, venueId, adminId,
                    correlationId));
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
     * It ends the long lock and a running short lockout, and both counts start again (PCR-012; E7-121).
     */
    record ResetOwnerPassword(UUID adminId, UUID correlationId, UUID venueAccountId, UUID venueId,
                              String ownerPasswordHash, AuditWriter audit, Notifications notifications,
                              OwnerPasswordAttempts attempts)
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
            // PCR-012 item 3 (E7-121): only this reset ends the long lock; the counts start again from zero
            attempts.endLocks(context, venueAccountId);
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
