package ps.e7gz.loyalty.internal;

import java.sql.ResultSet;
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
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;

/**
 * The points configuration (arch 01 §14 A8, PO decision D3 of 17 Oct 2026, FR-PTS-01, FR-PTS-07, FR-ADM-11; E7-68):
 * completed-booking points, referral points, the wallet-refund switch and, since PCR-010, the Points per fee unit - how
 * many Points pay one hour of Booking Fee (PER_HOUR) or one whole Booking Fee (PER_BOOKING); an even whole number from
 * 2, so a half hour is a whole number of Points. A change is one Platform Admin command that locks nothing, wrapped by
 * {@link Idempotency} (PO decision D6): each changed key goes through {@code loyalty.configure}, effective now and for
 * later events only - history keeps the value it was granted or paid with.
 *
 * <p>An omitted field keeps its value and a value equal to the current one changes nothing; a request that names no
 * field, or an odd Points per fee unit, is {@code VALIDATION_FAILED}.
 */
@Component
public class PointsSettings {

    static final String ATTENDED = "loyalty.attended_points";
    static final String REFERRAL = "loyalty.referral_points";
    static final String WALLET_REFUNDS = "loyalty.wallet_refunds_enabled";
    static final String FEE_UNIT = "loyalty.points_per_fee_unit";
    static final List<String> KEYS = List.of(ATTENDED, REFERRAL, WALLET_REFUNDS, FEE_UNIT);

    /** As config's Platform Fees: the latest version is in force; a launch value shows when it was installed. */
    static final String CURRENT = """
            SELECT DISTINCT ON (c.key) c.key, c.new_value #>> '{}' AS value,
                   CASE WHEN c.effective_from = '-infinity' THEN c.changed_at ELSE c.effective_from END AS effective_from
              FROM config.configuration_version c
             WHERE c.key = ANY(CAST(:keys AS text[]))
             ORDER BY c.key, c.effective_from DESC, c.version_position DESC""";

    static final String CONFIGURE = """
            SELECT loyalty.configure(:key, CAST(:value AS jsonb), :admin, :request, :reason)""";

    private final CommandRunner runner;
    private final Idempotency idempotency;
    private final JdbcClient admin;

    PointsSettings(CommandRunner runner, Idempotency idempotency,
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

    /** The points configuration in force. */
    public record Settings(Configured<Integer> attendedPoints, Configured<Integer> referralPoints,
                           Configured<Boolean> walletRefundsEnabled, Configured<Integer> pointsPerFeeUnit) {
    }

    /** The requested values; {@code null} keeps a value as it is. */
    public record Change(Integer attendedPoints, Integer referralPoints, Boolean walletRefundsEnabled,
                         Integer pointsPerFeeUnit, String reason) {
    }

    /** The configuration in force now, on the admin connection. */
    public Settings current() {
        return read(admin);
    }

    /**
     * Changes the named values once per Idempotency-Key; the answer is the configuration after the change.
     * {@code VALIDATION_FAILED} before any idempotency record when nothing is named, the reason is blank or the Points
     * per fee unit is odd or below 2.
     */
    public <R> IdempotentResult<R> change(UUID adminId, Change change, IdempotentRequest key, StoredResponse<R> response,
                                          Function<Settings, R> view) {
        Objects.requireNonNull(adminId, "adminId");
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(view, "view");
        if (change.attendedPoints() == null && change.referralPoints() == null && change.walletRefundsEnabled() == null
                && change.pointsPerFeeUnit() == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        if (change.reason() == null || change.reason().isBlank()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "reason");
        }
        // PCR-010 item 4: a half hour costs half a unit (loyalty.configure refuses it too)
        if (change.pointsPerFeeUnit() != null && (change.pointsPerFeeUnit() < 2 || change.pointsPerFeeUnit() % 2 != 0)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "pointsPerFeeUnit");
        }
        return runner.run(idempotency.wrap(Objects.requireNonNull(key, "key"), response,
                new ChangeSettings<>(adminId, CorrelationId.require(), change, view)));
    }

    private static Settings read(JdbcClient jdbc) {
        Map<String, Configured<String>> current = new LinkedHashMap<>();
        jdbc.sql(CURRENT).param("keys", KEYS.toArray(String[]::new))
                .query((ResultSet rs) -> {
                    current.put(rs.getString("key"), new Configured<>(rs.getString("value"),
                            rs.getObject("effective_from", OffsetDateTime.class).toInstant()));
                });
        return new Settings(typed(current, ATTENDED, Integer::valueOf), typed(current, REFERRAL, Integer::valueOf),
                typed(current, WALLET_REFUNDS, Boolean::valueOf), typed(current, FEE_UNIT, Integer::valueOf));
    }

    private static <T> Configured<T> typed(Map<String, Configured<String>> current, String key,
                                           Function<String, T> parse) {
        Configured<String> raw = current.get(key);
        if (raw == null) {
            throw new IllegalStateException("Configuration " + key + " has no version");
        }
        return new Configured<>(parse.apply(raw.value()), raw.effectiveFrom());
    }

    /**
     * No Venue in scope: category C, an internal platform operation (ADR-022). Locks nothing, which is what lets
     * {@code loyalty.configure} run inside it (a configuration-only Admin transaction, physical model v1.1.40).
     */
    private record ChangeSettings<R>(UUID adminId, UUID correlationId, Change change, Function<Settings, R> view)
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
            Settings before = read(context.jdbc());
            String reason = change.reason().strip();
            set(context, ATTENDED, change.attendedPoints(), before.attendedPoints(), reason);
            set(context, REFERRAL, change.referralPoints(), before.referralPoints(), reason);
            set(context, WALLET_REFUNDS, change.walletRefundsEnabled(), before.walletRefundsEnabled(), reason);
            set(context, FEE_UNIT, change.pointsPerFeeUnit(), before.pointsPerFeeUnit(), reason);
            return view.apply(read(context.jdbc()));
        }

        private <T> void set(CommandContext context, String key, T requested, Configured<T> current, String reason) {
            if (requested != null && !requested.equals(current.value())) {
                context.jdbc().sql(CONFIGURE).param("key", key).param("value", requested.toString())
                        .param("admin", adminId).param("request", UUID.randomUUID()).param("reason", reason)
                        .query(UUID.class).single();
            }
        }
    }
}
