package ps.e7gz.venue.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.validation.TextFields;
import ps.e7gz.common.validation.VenuePhones;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.identity.api.OwnerPasswordAttempts;
import ps.e7gz.identity.api.PasswordRules;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.notification.api.Notifications;
import ps.e7gz.notification.api.VenueBrowserPush;
import ps.e7gz.venue.api.AccountDisableBookings;
import ps.e7gz.venue.api.AccountDisableTournaments;
import ps.e7gz.venue.api.AccountRestrictions;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The venue's shared account (FR-VEN-01): creation by the Platform Admin (FR-ADM-02), the venue's own Owner Password
 * change (FR-VEN-46) and its settings (FR-VEN-40, PO decision 3 Oct 2026). Passwords are hashed here, before the
 * command opens, so no transaction waits on Argon2id for a new hash; the Owner Password check itself runs inside the
 * protected command (ADR-011).
 */
@Service
public class VenueAccounts implements VenueAccountVenues {

    /** PCR-006: exactly four ASCII digits. */
    private static final Pattern OWNER_PIN = Pattern.compile("[0-9]{4}");

    private final CommandRunner runner;
    private final OwnerPasswordGate gate;
    private final PasswordEncoder passwords;
    private final AuditWriter audit;
    private final AreaLookup areas;
    private final Notifications notifications;
    private final Outbox outbox;
    private final VenueBrowserPush browserPush;
    private final Idempotency idempotency;
    private final OwnerPasswordAttempts attempts;
    private final VenueAccountCommands.DisableDeps disableDeps;
    private final JdbcClient jdbc;

    /** A disable whose reach grew between its plan and its write plans again, this many times at most. */
    static final int STATUS_ATTEMPTS = 3;

    VenueAccounts(CommandRunner runner, OwnerPasswordGate gate, PasswordEncoder passwords, AuditWriter audit,
                  AreaLookup areas, Notifications notifications, Outbox outbox, VenueBrowserPush browserPush,
                  Idempotency idempotency, OwnerPasswordAttempts attempts, AccountDisableBookings disableBookings,
                  AccountDisableTournaments disableTournaments, AccountRestrictions restrictions,
                  DataSource appDataSource) {
        this.runner = runner;
        this.gate = gate;
        this.passwords = passwords;
        this.audit = audit;
        this.areas = areas;
        this.notifications = notifications;
        this.outbox = outbox;
        this.browserPush = browserPush;
        this.idempotency = idempotency;
        this.attempts = attempts;
        this.disableDeps = new VenueAccountCommands.DisableDeps(disableBookings, disableTournaments, restrictions,
                audit, outbox, browserPush);
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** The venue's profile and the account's Dashboard language, as the venue sees them. */
    public record Settings(Venue venue, String areaName, VenueAccountLanguage languagePreference) {
    }

    /** Changed settings; null means unchanged. */
    public record SettingsChanges(String name, String address, String phone, VenueAccountLanguage languagePreference) {
    }

    /**
     * Creates the venue's account with its first Owner Password (FR-ADM-02). Both passwords are hashed with Argon2id
     * and never stored, logged or returned in clear; the language is {@code en} when absent (FR-VEN-40).
     */
    public Venues.VenueAccountSummary create(UUID adminId, UUID venueId, String loginIdentifier, String password,
                                             String initialOwnerPassword, VenueAccountLanguage language) {
        requireVenue(venueId);
        String login = TextFields.optional(loginIdentifier, "loginIdentifier");
        requireAcceptable(password, "password");
        requireOwnerPin(initialOwnerPassword, "initialOwnerPassword");
        String passwordHash = passwords.encode(password);
        String ownerPasswordHash = passwords.encode(initialOwnerPassword);
        return runner.run(new VenueAccountCommands.Create(adminId, CorrelationId.require(), venueId, login,
                passwordHash, ownerPasswordHash, language == null ? VenueAccountLanguage.en : language, audit));
    }

    /** FR-VEN-46: the current Owner Password authorises the change; the new one works at once, the old one never again. */
    public void changeOwnerPassword(UUID venueAccountId, String currentPassword, String nextPassword) {
        UUID venueId = venueOf(venueAccountId);
        requireOwnerPin(nextPassword, "next");
        String nextHash = passwords.encode(nextPassword);
        UUID correlationId = CorrelationId.require();
        gate.run(new VenueAccountCommands.ChangeOwnerPassword(venueAccountId, venueId, correlationId, currentPassword,
                nextHash, gate, audit), venueAccountId, correlationId);
    }

    /**
     * The Platform Admin disables or re-enables the account (FR-ADM-03) with a reason, with every O10 consequence of a
     * disable in the same transaction (E7-78; {@link VenueAccountCommands.SetAccountStatus}). Without an
     * Idempotency-Key: for in-process callers only.
     */
    public Venues.VenueAccountSummary setStatus(UUID adminId, UUID venueAccountId, VenueAccountStatus target,
                                                String reason) {
        return setStatus(adminId, venueAccountId, target, reason, runner::run);
    }

    /**
     * The admin route: once per Idempotency-Key (ADR-015). A disable cancels AWAITING bookings and moves their Booking
     * Fee refunds, so a replay with the same key returns the stored answer and changes nothing again (PO decision D4,
     * 19 Nov 2026, E7-77).
     */
    public Venues.VenueAccountSummary setStatus(UUID adminId, UUID venueAccountId, VenueAccountStatus target,
                                                String reason, IdempotentRequest key) {
        Objects.requireNonNull(key, "key");
        StoredResponse<Venues.VenueAccountSummary> response = StoredResponse.of(Venues.VenueAccountSummary.class, 200);
        return setStatus(adminId, venueAccountId, target, reason,
                command -> runner.run(idempotency.wrap(key, response, command)).body());
    }

    /** Plans the lock set, then writes under it; a reach that grew in between plans again (as E7-82 does). */
    private Venues.VenueAccountSummary setStatus(UUID adminId, UUID venueAccountId, VenueAccountStatus target,
                                                 String reason,
                                                 Function<VenueAccountCommands.SetAccountStatus,
                                                         Venues.VenueAccountSummary> run) {
        UUID venueId = venueOf(venueAccountId);
        UUID correlationId = CorrelationId.require();
        String stripped = reason.strip();
        for (int attempt = 1; ; attempt++) {
            LockSet locks = runner.run(new VenueAccountCommands.PlanStatus(venueAccountId, venueId, target,
                    disableDeps));
            try {
                return run.apply(new VenueAccountCommands.SetAccountStatus(adminId, correlationId, venueAccountId,
                        venueId, target, stripped, locks, disableDeps));
            } catch (VenueAccountCommands.StaleLocks grown) {
                if (attempt >= STATUS_ATTEMPTS) {
                    throw new IllegalStateException("The Venue Account disable kept reaching new rows", grown);
                }
            }
        }
    }

    /** One row of the Platform Admin's venue-account list: never a password, hash or Owner PIN (E7-37 carry-forward). */
    public record AccountListItem(UUID id, UUID venueId, String loginIdentifier, VenueAccountStatus accountStatus,
                                  String venueName, VenueAccountLanguage languagePreference) {
    }

    public record AccountPage(List<AccountListItem> items, String nextCursor) {
    }

    static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * FR-ADM-03 (PO decision 4 Oct 2026, carry-forward from E7-37 to E7-78): every venue account with its venue's name,
     * ordered by venue name then account id, optionally only one {@code accountStatus}. The cursor is the opaque
     * (venue name, id) of the last row of the previous page, so each account appears once across the pages.
     */
    public AccountPage list(VenueAccountStatus accountStatus, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        AccountCursor after = cursor == null || cursor.isBlank() ? null : AccountCursor.decode(cursor);
        List<AccountListItem> rows = jdbc.sql("""
                        SELECT a.id, a.venue_id, a.login_identifier, a.account_status, a.language_preference,
                               v.name AS venue_name
                          FROM iam.venue_account a
                          JOIN venue.venue v ON v.id = a.venue_id
                         WHERE (CAST(:status AS text) IS NULL OR a.account_status = :status)
                           AND (CAST(:afterId AS uuid) IS NULL OR (v.name, a.id) > (CAST(:afterName AS text), :afterId))
                         ORDER BY v.name, a.id
                         LIMIT :limit""")
                .param("status", accountStatus == null ? null : accountStatus.name())
                .param("afterName", after == null ? null : after.venueName())
                .param("afterId", after == null ? null : after.id())
                .param("limit", size + 1)
                .query((rs, n) -> new AccountListItem(rs.getObject("id", UUID.class),
                        rs.getObject("venue_id", UUID.class), rs.getString("login_identifier"),
                        VenueAccountStatus.valueOf(rs.getString("account_status")), rs.getString("venue_name"),
                        VenueAccountLanguage.valueOf(rs.getString("language_preference"))))
                .list();
        boolean more = rows.size() > size;
        List<AccountListItem> page = more ? rows.subList(0, size) : rows;
        return new AccountPage(List.copyOf(page),
                more ? new AccountCursor(page.getLast().venueName(), page.getLast().id()).encode() : null);
    }

    /** The keyset of the list: the venue name and the account id, Base64url over the 16 id bytes then the UTF-8 name. */
    record AccountCursor(String venueName, UUID id) {

        String encode() {
            byte[] name = venueName.getBytes(StandardCharsets.UTF_8);
            ByteBuffer bytes = ByteBuffer.allocate(16 + name.length).putLong(id.getMostSignificantBits())
                    .putLong(id.getLeastSignificantBits()).put(name);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
        }

        static AccountCursor decode(String cursor) {
            try {
                byte[] bytes = Base64.getUrlDecoder().decode(cursor);
                if (bytes.length < 16) {
                    throw new IllegalArgumentException("cursor length");
                }
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                UUID id = new UUID(buffer.getLong(), buffer.getLong());
                return new AccountCursor(new String(bytes, 16, bytes.length - 16, StandardCharsets.UTF_8), id);
            } catch (IllegalArgumentException malformed) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
            }
        }
    }

    /** FR-ADM-03: the admin chooses the new login password; it is hashed and never returned. */
    public void resetLoginPassword(UUID adminId, UUID venueAccountId, String newPassword) {
        UUID venueId = venueOf(venueAccountId);
        requireAcceptable(newPassword, "newPassword");
        String hash = passwords.encode(newPassword);
        runner.run(new VenueAccountCommands.ResetLoginPassword(adminId, CorrelationId.require(), venueAccountId,
                venueId, hash, audit));
    }

    /**
     * FR-ADM-03: the admin chooses the new Owner Password (a 4-digit PIN); the old one stops working at once. It also
     * ends the long lock and a running short lockout (PCR-012 item 3, E7-121).
     */
    public void resetOwnerPassword(UUID adminId, UUID venueAccountId, String newPassword) {
        UUID venueId = venueOf(venueAccountId);
        requireOwnerPin(newPassword, "newPassword");
        String hash = passwords.encode(newPassword);
        runner.run(new VenueAccountCommands.ResetOwnerPassword(adminId, CorrelationId.require(), venueAccountId,
                venueId, hash, audit, notifications, attempts));
    }

    public Settings settings(UUID venueAccountId) {
        UUID venueId = venueOf(venueAccountId);
        Venue venue = jdbc.sql(Venue.SELECT + " WHERE v.id = :id").param("id", venueId).query(Venue::map).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        VenueAccountLanguage language = jdbc.sql("SELECT language_preference FROM iam.venue_account WHERE id = :id")
                .param("id", venueAccountId).query(String.class).optional().map(VenueAccountLanguage::valueOf)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        return new Settings(venue, areas.name(venue.areaId()).orElseThrow(), language);
    }

    /**
     * Edits only the fields sent, under the Platform Admin's venue-edit rules (arch 09 §4.2): a text field that is sent
     * must not be blank, and the phone is stored as E.164 ({@code VALIDATION_FAILED} with the field).
     */
    public Settings updateSettings(UUID venueAccountId, SettingsChanges changes) {
        UUID venueId = venueOf(venueAccountId);
        String name = TextFields.optional(changes.name(), "name");
        String address = TextFields.optional(changes.address(), "address");
        String phone = changes.phone() == null ? null : VenuePhones.toE164(changes.phone())
                .orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED, "phone"));
        runner.run(new VenueAccountCommands.UpdateSettings(venueAccountId, venueId, name, address, phone,
                changes.languagePreference()));
        return settings(venueAccountId);
    }

    /** The venue of the signed-in account; the venue routes only ever act on this venue. */
    @Override
    public UUID venueOf(UUID venueAccountId) {
        return jdbc.sql("SELECT venue_id FROM iam.venue_account WHERE id = :id").param("id", venueAccountId)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    /**
     * The venue login password follows the customer password rule: at least 8 characters with a letter and a digit
     * (VALIDATION RULES §2.1; Product Owner decision 3 Oct 2026, E7-24).
     */
    private static void requireAcceptable(String password, String field) {
        if (!PasswordRules.isAcceptable(password)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, field);
        }
    }

    /** The Owner Password is a PIN of exactly 4 digits, 0-9 (PCR-006). */
    static boolean isOwnerPin(String value) {
        return value != null && OWNER_PIN.matcher(value).matches();
    }

    private static void requireOwnerPin(String value, String field) {
        if (!isOwnerPin(value)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, field);
        }
    }

    /** {@code admin.begin_command} locks the venue row, so an unknown id is answered before the command opens. */
    private void requireVenue(UUID venueId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM venue.venue WHERE id = :id)").param("id", venueId)
                .query(Boolean.class).single();
        if (!exists) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }
}
