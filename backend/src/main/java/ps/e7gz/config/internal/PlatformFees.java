package ps.e7gz.config.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingFeeMode;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * The Platform Fees (FR-ADM-10, FR-ADM-11, arch 01 §14 A8, ADR-028; E7-68): the Booking Fee - its mode, the per-booking
 * and the per-hour amount (PCR-010) - and the Tournament Creation Fee. A change is one Platform Admin command that
 * locks nothing, wrapped by {@link Idempotency} (PO decision D6): each changed key goes through
 * {@code config.change_configuration}, which records the admin, the reason and the version, effective at the command
 * instant and for later events only - every booking keeps the fee stored at its creation (FR-PTS-13, FR-AUD-01).
 *
 * <p>An omitted field keeps its value, and a value equal to the current one changes nothing (the producer refuses an
 * unchanged value, so it is skipped here); a request that names no field is {@code VALIDATION_FAILED}.
 */
@Component
public class PlatformFees {

    static final String MODE = "booking.fee_mode";
    static final String PER_BOOKING = "booking.fee";
    static final String PER_HOUR = "booking.fee_per_hour";
    static final String TOURNAMENT = "tournament.creation_fee";
    static final List<String> KEYS = List.of(MODE, PER_BOOKING, PER_HOUR, TOURNAMENT);

    /**
     * The latest version of each key: versions are future-only and take effect when recorded, so the latest is the one
     * in force. The launch value's version takes effect from -infinity; it shows the instant it was installed.
     */
    static final String CURRENT = """
            SELECT DISTINCT ON (c.key) c.key, c.new_value #>> '{}' AS value,
                   CASE WHEN c.effective_from = '-infinity' THEN c.changed_at ELSE c.effective_from END AS effective_from
              FROM config.configuration_version c
             WHERE c.key = ANY(CAST(:keys AS text[]))
             ORDER BY c.key, c.effective_from DESC, c.version_position DESC""";

    static final String CHANGE = "SELECT config.change_configuration(:key, CAST(:value AS jsonb), :reason)";

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final JdbcClient admin;

    PlatformFees(CommandRunner runner, Idempotency idempotency,
                 @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE) DataSource adminDataSource) {
        this.runner = runner;
        this.idempotency = idempotency;
        this.admin = JdbcClient.create(adminDataSource);
    }

    /** One configured value and the instant it took effect. */
    public record Configured<T>(T value, Instant effectiveFrom) {

        public Configured {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        }
    }

    /** The Platform Fees in force. */
    public record Fees(Configured<BookingFeeMode> bookingFeeMode, Configured<Money> bookingFee,
                       Configured<Money> bookingFeePerHour, Configured<Money> tournamentCreationFee) {
    }

    /** The requested values; {@code null} keeps a value as it is. */
    public record Change(BookingFeeMode bookingFeeMode, Money bookingFee, Money bookingFeePerHour,
                         Money tournamentCreationFee, String reason) {
    }

    /** The fees in force now, on the admin connection. */
    public Fees current() {
        return read(admin);
    }

    /**
     * Changes the named fees once per Idempotency-Key; the answer is the fees after the change. {@code VALIDATION_FAILED}
     * before any idempotency record when nothing is named or the reason is blank.
     */
    public <R> IdempotentResult<R> change(UUID adminId, Change change, IdempotentRequest key, StoredResponse<R> response,
                                          Function<Fees, R> view) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(view, "view");
        if (change.bookingFeeMode() == null && change.bookingFee() == null && change.bookingFeePerHour() == null
                && change.tournamentCreationFee() == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        if (change.reason() == null || change.reason().isBlank()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "reason");
        }
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new ChangeFees<>(adminId, CorrelationId.require(), change, view)));
    }

    private static Fees read(JdbcClient jdbc) {
        Map<String, Configured<String>> current = new LinkedHashMap<>();
        jdbc.sql(CURRENT).param("keys", KEYS.toArray(String[]::new))
                .query((ResultSet rs) -> {
                    current.put(rs.getString("key"), new Configured<>(rs.getString("value"), effectiveFrom(rs)));
                });
        return new Fees(typed(current, MODE, BookingFeeMode::valueOf), typed(current, PER_BOOKING, Money::of),
                typed(current, PER_HOUR, Money::of), typed(current, TOURNAMENT, Money::of));
    }

    private static Instant effectiveFrom(ResultSet rs) throws SQLException {
        return rs.getObject("effective_from", OffsetDateTime.class).toInstant();
    }

    private static <T> Configured<T> typed(Map<String, Configured<String>> current, String key,
                                           Function<String, T> parse) {
        Configured<String> raw = current.get(key);
        if (raw == null) {
            throw new IllegalStateException("Configuration " + key + " has no version");
        }
        return new Configured<>(parse.apply(raw.value()), raw.effectiveFrom());
    }

    /** No Venue in scope: category C, an internal platform operation (ADR-022). Locks nothing. */
    private record ChangeFees<R>(UUID adminId, UUID correlationId, Change change, Function<Fees, R> view)
            implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.admin(adminId, correlationId, LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            Fees before = read(context.jdbc());
            String reason = change.reason().strip();
            if (change.bookingFeeMode() != null && change.bookingFeeMode() != before.bookingFeeMode().value()) {
                set(context, MODE, "\"" + change.bookingFeeMode().name() + "\"", reason);
            }
            setMoney(context, PER_BOOKING, change.bookingFee(), before.bookingFee(), reason);
            setMoney(context, PER_HOUR, change.bookingFeePerHour(), before.bookingFeePerHour(), reason);
            setMoney(context, TOURNAMENT, change.tournamentCreationFee(), before.tournamentCreationFee(), reason);
            return view.apply(read(context.jdbc()));
        }

        private static void setMoney(CommandContext context, String key, Money requested, Configured<Money> current,
                                     String reason) {
            if (requested != null && requested.amount().compareTo(current.value().amount()) != 0) {
                set(context, key, requested.toString(), reason);
            }
        }

        private static void set(CommandContext context, String key, String json, String reason) {
            context.jdbc().sql(CHANGE).param("key", key).param("value", json).param("reason", reason)
                    .query(UUID.class).single();
        }
    }
}
