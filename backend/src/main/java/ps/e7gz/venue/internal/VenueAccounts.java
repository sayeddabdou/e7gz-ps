package ps.e7gz.venue.internal;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.validation.TextFields;
import ps.e7gz.common.validation.VenuePhones;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.identity.api.PasswordRules;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * The venue's shared account (FR-VEN-01): creation by the Platform Admin (FR-ADM-02), the venue's own Owner Password
 * change (FR-VEN-46) and its settings (FR-VEN-40, PO decision 3 Oct 2026). Passwords are hashed here, before the
 * command opens, so no transaction waits on Argon2id for a new hash; the Owner Password check itself runs inside the
 * protected command (ADR-011).
 */
@Service
public class VenueAccounts {

    private final CommandRunner runner;
    private final OwnerPasswordGate gate;
    private final PasswordEncoder passwords;
    private final AuditWriter audit;
    private final AreaLookup areas;
    private final JdbcClient jdbc;

    VenueAccounts(CommandRunner runner, OwnerPasswordGate gate, PasswordEncoder passwords, AuditWriter audit,
                  AreaLookup areas, DataSource appDataSource) {
        this.runner = runner;
        this.gate = gate;
        this.passwords = passwords;
        this.audit = audit;
        this.areas = areas;
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
        requireAcceptable(initialOwnerPassword, "initialOwnerPassword");
        String passwordHash = passwords.encode(password);
        String ownerPasswordHash = passwords.encode(initialOwnerPassword);
        return runner.run(new VenueAccountCommands.Create(adminId, CorrelationId.require(), venueId, login,
                passwordHash, ownerPasswordHash, language == null ? VenueAccountLanguage.en : language, audit));
    }

    /** FR-VEN-46: the current Owner Password authorises the change; the new one works at once, the old one never again. */
    public void changeOwnerPassword(UUID venueAccountId, String currentPassword, String nextPassword) {
        UUID venueId = venueOf(venueAccountId);
        requireAcceptable(nextPassword, "next");
        String nextHash = passwords.encode(nextPassword);
        UUID correlationId = CorrelationId.require();
        gate.run(new VenueAccountCommands.ChangeOwnerPassword(venueAccountId, venueId, correlationId, currentPassword,
                nextHash, gate, audit), venueAccountId, correlationId);
    }

    /**
     * The Platform Admin disables or re-enables the account (FR-ADM-03) with a reason. Only Dashboard access changes
     * here; the booking and tournament consequences of a disable (O10) come with E7-78.
     */
    public Venues.VenueAccountSummary setStatus(UUID adminId, UUID venueAccountId, VenueAccountStatus target,
                                                String reason) {
        UUID venueId = venueOf(venueAccountId);
        return runner.run(new VenueAccountCommands.SetAccountStatus(adminId, CorrelationId.require(), venueAccountId,
                venueId, target, reason.strip(), audit));
    }

    /** FR-ADM-03: the admin chooses the new login password; it is hashed and never returned. */
    public void resetLoginPassword(UUID adminId, UUID venueAccountId, String newPassword) {
        UUID venueId = venueOf(venueAccountId);
        requireAcceptable(newPassword, "newPassword");
        String hash = passwords.encode(newPassword);
        runner.run(new VenueAccountCommands.ResetLoginPassword(adminId, CorrelationId.require(), venueAccountId,
                venueId, hash, audit));
    }

    /** FR-ADM-03: the admin chooses the new Owner Password; the old one stops working at once. */
    public void resetOwnerPassword(UUID adminId, UUID venueAccountId, String newPassword) {
        UUID venueId = venueOf(venueAccountId);
        requireAcceptable(newPassword, "newPassword");
        String hash = passwords.encode(newPassword);
        runner.run(new VenueAccountCommands.ResetOwnerPassword(adminId, CorrelationId.require(), venueAccountId,
                venueId, hash, audit));
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
    public UUID venueOf(UUID venueAccountId) {
        return jdbc.sql("SELECT venue_id FROM iam.venue_account WHERE id = :id").param("id", venueAccountId)
                .query(UUID.class).optional().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    /**
     * Venue login and Owner Passwords follow the customer password rule: at least 8 characters with a letter and a
     * digit (VALIDATION RULES §2.1; Product Owner decision 3 Oct 2026, E7-24).
     */
    private static void requireAcceptable(String password, String field) {
        if (!PasswordRules.isAcceptable(password)) {
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
