package ps.e7gz.loyalty.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.loyalty.api.BookingFeeRefunds;

/**
 * {@code loyalty.begin_command} and {@code loyalty.record_refund} on the command's transaction: the producer functions
 * are the only writers of {@code loyalty.booking_fee_refund} (ADR-026). A venue answer never ends in an ADMIN_* reason,
 * so it needs no service-loss assessment; the Admin no-show reversal (E7-70) records one with
 * {@code loyalty.assess_service_loss}.
 */
@Component
class JdbcBookingFeeRefunds implements BookingFeeRefunds {

    static final String BEGIN = "SELECT loyalty.begin_command(ARRAY[CAST(:user AS uuid)], :correlation)";
    /**
     * E7-113: every Customer of a batch in one context. A {@code UUID[]} parameter goes to the driver as is (only an
     * {@code Iterable} is expanded by Spring) and pgjdbc sends it as an untyped array literal - the cast types it.
     */
    static final String BEGIN_MANY = "SELECT loyalty.begin_command(CAST(:users AS uuid[]), :correlation)";
    static final String RECORD = "SELECT loyalty.record_refund(:booking, :history, NULL)";
    static final String CONTEXT_OPEN = "SELECT to_regclass('pg_temp.e7gz_loyalty_command_context') IS NOT NULL";

    /** E7-70, admin runtime only: the explicit service-loss assessment of an Admin reason and the decision on it. */
    static final String ASSESS = "SELECT loyalty.assess_service_loss(:booking, :history, :loss, :reason)";
    static final String RECORD_ASSESSED = "SELECT loyalty.record_refund(:booking, :history, :assessment)";

    /** The order a batch is recorded in: by Customer, as the lock set orders them, then by booking. */
    static final Comparator<Decision> ORDER =
            Comparator.comparing(Decision::customerId).thenComparing(Decision::bookingId);

    @Override
    public UUID recordDecision(CommandContext command, UUID customerId, UUID bookingId, UUID terminalHistoryId) {
        Objects.requireNonNull(command, "command");
        command.jdbc().sql(BEGIN)
                .param("user", Objects.requireNonNull(customerId, "customerId"))
                .param("correlation", CorrelationId.require())
                .query().singleRow();
        return command.jdbc().sql(RECORD)
                .param("booking", Objects.requireNonNull(bookingId, "bookingId"))
                .param("history", Objects.requireNonNull(terminalHistoryId, "terminalHistoryId"))
                .query(UUID.class).single();
    }

    @Override
    public Map<UUID, UUID> recordDecisions(CommandContext command, List<Decision> decisions) {
        Objects.requireNonNull(command, "command");
        List<Decision> ordered = validated(decisions);
        if (ordered.isEmpty()) {
            return Map.of();
        }
        // E7-71: a Ban cascade inside an Admin correction joins the Admin's Loyalty context, which that command opened
        // before its Trust write; one immutable context per transaction (loyalty.start_command), so never a second one
        if (!loyaltyCommandOpen(command)) {
            command.jdbc().sql(BEGIN_MANY)
                    .param("users", ordered.stream().map(Decision::customerId).distinct().sorted()
                            .toArray(UUID[]::new))
                    .param("correlation", CorrelationId.require())
                    .query().singleRow();
        }
        Map<UUID, UUID> refunds = new LinkedHashMap<>();
        for (Decision decision : ordered) {
            refunds.put(decision.bookingId(), command.jdbc().sql(RECORD)
                    .param("booking", decision.bookingId())
                    .param("history", decision.terminalHistoryId())
                    .query(UUID.class).single());
        }
        return Collections.unmodifiableMap(refunds);
    }

    @Override
    public UUID recordAdminDecision(CommandContext command, UUID adminId, UUID customerId, UUID bookingId,
                                    UUID historyId, boolean serviceLoss, String reason) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(historyId, "historyId");
        command.jdbc().sql(JdbcBookingRewards.BEGIN_ADMIN)
                .param("user", Objects.requireNonNull(customerId, "customerId"))
                .param("admin", Objects.requireNonNull(adminId, "adminId"))
                .param("correlation", CorrelationId.require())
                .query().singleRow();
        UUID assessment = command.jdbc().sql(ASSESS)
                .param("booking", bookingId).param("history", historyId).param("loss", serviceLoss)
                .param("reason", Objects.requireNonNull(reason, "reason"))
                .query(UUID.class).single();
        return command.jdbc().sql(RECORD_ASSESSED)
                .param("booking", bookingId).param("history", historyId).param("assessment", assessment)
                .query(UUID.class).single();
    }

    /**
     * Whether this transaction already opened its Loyalty command: the context table is created ON COMMIT DROP by
     * {@code loyalty.start_command}, so its existence means this transaction's context. Its Customers must still cover
     * the batch - {@code loyalty.record_refund} refuses otherwise ({@code loyalty.command_context}).
     */
    static boolean loyaltyCommandOpen(CommandContext command) {
        return Boolean.TRUE.equals(command.jdbc().sql(CONTEXT_OPEN).query(Boolean.class).single());
    }

    /** The whole batch checked before any SQL, in recording order; a booking twice is a caller defect. */
    private static List<Decision> validated(List<Decision> decisions) {
        Objects.requireNonNull(decisions, "decisions");
        List<Decision> ordered = new ArrayList<>(decisions.size());
        Set<UUID> bookings = new HashSet<>();
        for (Decision decision : decisions) {
            Objects.requireNonNull(decision, "decisions must not contain null");
            if (!bookings.add(decision.bookingId())) {
                throw new IllegalArgumentException("Booking " + decision.bookingId() + " appears twice in the batch");
            }
            ordered.add(decision);
        }
        ordered.sort(ORDER);
        return ordered;
    }
}
